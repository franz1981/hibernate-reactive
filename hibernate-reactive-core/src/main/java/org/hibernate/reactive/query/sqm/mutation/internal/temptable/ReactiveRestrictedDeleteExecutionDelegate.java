/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.query.sqm.mutation.internal.temptable;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.hibernate.dialect.temptable.TemporaryTable;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.util.MutableBoolean;
import org.hibernate.internal.util.MutableInteger;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.MappingModelExpressible;
import org.hibernate.metamodel.mapping.SelectableConsumer;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationHelper;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.Joinable;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.spi.QueryParameterBindings;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.internal.SqmJdbcExecutionContextAdapter;
import org.hibernate.query.sqm.internal.SqmUtil;
import org.hibernate.query.sqm.mutation.internal.MultiTableSqmMutationConverter;
import org.hibernate.query.sqm.mutation.internal.TableKeyExpressionCollector;
import org.hibernate.query.sqm.mutation.internal.temptable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.temptable.ExecuteWithoutIdTableHelper;
import org.hibernate.query.sqm.mutation.internal.temptable.RestrictedDeleteExecutionDelegate;
import org.hibernate.query.sqm.spi.SqmParameterMappingModelResolutionAccess;
import org.hibernate.query.sqm.tree.delete.SqmDeleteStatement;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.reactive.logging.impl.Log;
import org.hibernate.reactive.logging.impl.LoggerFactory;
import org.hibernate.reactive.query.sqm.mutation.internal.ReactiveSqmMutationStrategyHelper;
import org.hibernate.reactive.sql.exec.internal.StandardReactiveJdbcMutationExecutor;
import org.hibernate.reactive.util.impl.CompletionStages;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.spi.SqlExpressionResolver;
import org.hibernate.sql.ast.tree.delete.DeleteStatement;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.from.MutatingTableReferenceGroupWrapper;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableReference;
import org.hibernate.sql.ast.tree.from.UnionTableReference;
import org.hibernate.sql.ast.tree.predicate.InSubQueryPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.PredicateCollector;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcOperationQueryDelete;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;

import static org.hibernate.query.sqm.mutation.internal.temptable.ExecuteWithTemporaryTableHelper.createIdTableSelectQuerySpec;
import static org.hibernate.reactive.util.impl.CompletionStages.completedFuture;
import static org.hibernate.reactive.util.impl.CompletionStages.voidFuture;

/**
 * The reactive version of {@link RestrictedDeleteExecutionDelegate}
 */
// Basically a copy of RestrictedDeleteExecutionDelegate, we will probably need to refactor this code to avoid
// duplication
public class ReactiveRestrictedDeleteExecutionDelegate
		implements ReactiveTableBasedDeleteHandler.ReactiveExecutionDelegate {

	private static final Log LOG = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private final EntityMappingType entityDescriptor;
	private final TemporaryTable idTable;
	private final AfterUseAction afterUseAction;
	private final SqmDeleteStatement<?> sqmDelete;
	private final DomainParameterXref domainParameterXref;
	private final SessionFactoryImplementor sessionFactory;

	private final Function<SharedSessionContractImplementor, String> sessionUidAccess;
	private final MultiTableSqmMutationConverter converter;

	public ReactiveRestrictedDeleteExecutionDelegate(
			EntityMappingType entityDescriptor,
			TemporaryTable idTable,
			AfterUseAction afterUseAction,
			SqmDeleteStatement<?> sqmDelete,
			DomainParameterXref domainParameterXref,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			QueryOptions queryOptions,
			LoadQueryInfluencers loadQueryInfluencers,
			QueryParameterBindings queryParameterBindings,
			SessionFactoryImplementor sessionFactory) {
		this.entityDescriptor = entityDescriptor;
		this.idTable = idTable;
		this.afterUseAction = afterUseAction;
		this.sqmDelete = sqmDelete;
		this.domainParameterXref = domainParameterXref;
		this.sessionUidAccess = sessionUidAccess;
		this.sessionFactory = sessionFactory;
		this.converter = new MultiTableSqmMutationConverter(
				entityDescriptor,
				sqmDelete,
				sqmDelete.getTarget(),
				domainParameterXref,
				queryOptions,
				loadQueryInfluencers,
				queryParameterBindings,
				sessionFactory
		);
	}

	@Override
	public CompletionStage<Integer> reactiveExecute(DomainQueryExecutionContext executionContext) {
		final EntityPersister entityDescriptor = sessionFactory.getRuntimeMetamodels()
				.getMappingMetamodel()
				.getEntityDescriptor( sqmDelete.getTarget().getEntityName() );
		final String hierarchyRootTableName = ( (Joinable) entityDescriptor ).getTableName();

		final TableGroup deletingTableGroup = converter.getMutatingTableGroup();

		final TableReference hierarchyRootTableReference = deletingTableGroup.resolveTableReference(
				deletingTableGroup.getNavigablePath(),
				hierarchyRootTableName
		);
		assert hierarchyRootTableReference != null;

		final Map<SqmParameter<?>, List<List<JdbcParameter>>> parameterResolutions;
		final Map<SqmParameter<?>, MappingModelExpressible<?>> paramTypeResolutions;

		if ( domainParameterXref.getSqmParameterCount() == 0 ) {
			parameterResolutions = Collections.emptyMap();
			paramTypeResolutions = Collections.emptyMap();
		}
		else {
			parameterResolutions = new IdentityHashMap<>();
			paramTypeResolutions = new LinkedHashMap<>();
		}

		// Use the converter to interpret the where-clause.  We do this for 2 reasons:
		//		1) the resolved Predicate is ultimately the base for applying restriction to the deletes
		//		2) we also inspect each ColumnReference that is part of the where-clause to see which
		//			table it comes from.  if all of the referenced columns (if any at all) are from the root table
		//			we can perform all of the deletes without using an id-table
		final MutableBoolean needsIdTableWrapper = new MutableBoolean( false );
		final Predicate specifiedRestriction = converter.visitWhereClause(
				sqmDelete.getWhereClause(),
				columnReference -> {
					if ( !hierarchyRootTableReference.getIdentificationVariable()
							.equals( columnReference.getQualifier() ) ) {
						needsIdTableWrapper.setValue( true );
					}
				},
				(sqmParameter, mappingType, jdbcParameters) -> {
					parameterResolutions.computeIfAbsent(
							sqmParameter,
							k -> new ArrayList<>( 1 )
					).add( jdbcParameters );
					paramTypeResolutions.put( sqmParameter, mappingType );
				}
		);

		final PredicateCollector predicateCollector = new PredicateCollector( specifiedRestriction );
		entityDescriptor.applyBaseRestrictions(
				(filterPredicate) -> {
					needsIdTableWrapper.setValue( true );
					predicateCollector.applyPredicate( filterPredicate );
				},
				deletingTableGroup,
				true,
				executionContext.getSession().getLoadQueryInfluencers().getEnabledFilters(),
				null,
				converter
		);

		converter.pruneTableGroupJoins();

		// We need an id table if we want to delete from an intermediate table to avoid FK violations
		// The intermediate table has a FK to the root table, so we can't delete from the root table first
		// Deleting from the intermediate table first also isn't possible,
		// because that is the source for deletion in other tables, hence we need an id table
		final boolean needsIdTable = needsIdTableWrapper.getValue()
				|| entityDescriptor != entityDescriptor.getRootEntityDescriptor();

		final SqmJdbcExecutionContextAdapter executionContextAdapter = SqmJdbcExecutionContextAdapter.omittingLockingAndPaging(
				executionContext );

		if ( needsIdTable ) {
			return executeWithIdTable(
					predicateCollector.getPredicate(),
					deletingTableGroup,
					parameterResolutions,
					paramTypeResolutions,
					executionContextAdapter
			);
		}
		else {
			return executeWithoutIdTable(
					predicateCollector.getPredicate(),
					deletingTableGroup,
					parameterResolutions,
					paramTypeResolutions,
					converter.getSqlExpressionResolver(),
					executionContextAdapter
			);
		}
	}

	private CompletionStage executeWithoutIdTable(
			Predicate suppliedPredicate,
			TableGroup tableGroup,
			Map<SqmParameter<?>, List<List<JdbcParameter>>> restrictionSqmParameterResolutions,
			Map<SqmParameter<?>, MappingModelExpressible<?>> paramTypeResolutions,
			SqlExpressionResolver sqlExpressionResolver,
			ExecutionContext executionContext) {
		assert entityDescriptor == entityDescriptor.getRootEntityDescriptor();

		final EntityPersister rootEntityPersister = entityDescriptor.getEntityPersister();
		final String rootTableName = ( (Joinable) rootEntityPersister ).getTableName();
		final NamedTableReference rootTableReference = (NamedTableReference) tableGroup.resolveTableReference(
				tableGroup.getNavigablePath(),
				rootTableName
		);

		final QuerySpec matchingIdSubQuerySpec = ExecuteWithoutIdTableHelper.createIdMatchingSubQuerySpec(
				tableGroup.getNavigablePath(),
				rootTableReference,
				suppliedPredicate,
				rootEntityPersister,
				sqlExpressionResolver,
				sessionFactory
		);

		final JdbcParameterBindings jdbcParameterBindings = SqmUtil.createJdbcParameterBindings(
				executionContext.getQueryParameterBindings(),
				domainParameterXref,
				SqmUtil.generateJdbcParamsXref(
						domainParameterXref,
						() -> restrictionSqmParameterResolutions
				),
				sessionFactory.getRuntimeMetamodels().getMappingMetamodel(),
				navigablePath -> tableGroup,
				new SqmParameterMappingModelResolutionAccess() {
					@Override
					@SuppressWarnings("unchecked")
					public <T> MappingModelExpressible<T> getResolvedMappingModelType(SqmParameter<T> parameter) {
						return (MappingModelExpressible<T>) paramTypeResolutions.get( parameter );
					}
				},
				executionContext.getSession()
		);

		CompletionStage<Void> cleanUpCollectionTablesStage = ReactiveSqmMutationStrategyHelper.cleanUpCollectionTables(
				entityDescriptor,
				(tableReference, attributeMapping) -> {
					// No need for a predicate if there is no supplied predicate i.e. this is a full cleanup
					if ( suppliedPredicate == null ) {
						return null;
					}
					final ForeignKeyDescriptor fkDescriptor = attributeMapping.getKeyDescriptor();
					final QuerySpec idSelectFkSubQuery;
					// todo (6.0): based on the location of the attribute mapping, we could prune the table group of the subquery
					if ( fkDescriptor.getTargetPart().isEntityIdentifierMapping() ) {
						idSelectFkSubQuery = matchingIdSubQuerySpec;
					}
					else {
						idSelectFkSubQuery = ExecuteWithoutIdTableHelper.createIdMatchingSubQuerySpec(
								tableGroup.getNavigablePath(),
								rootTableReference,
								suppliedPredicate,
								rootEntityPersister,
								sqlExpressionResolver,
								sessionFactory
						);
					}
					return new InSubQueryPredicate(
							MappingModelCreationHelper.buildColumnReferenceExpression(
									new MutatingTableReferenceGroupWrapper(
											new NavigablePath( attributeMapping.getRootPathName() ),
											attributeMapping,
											(NamedTableReference) tableReference
									),
									fkDescriptor,
									null,
									sessionFactory
							),
							idSelectFkSubQuery,
							false
					);

				},
				jdbcParameterBindings,
				executionContext
		);
		if ( rootTableReference instanceof UnionTableReference ) {
			final CompletionStage<Void>[] deleteFromNonRootStages = new CompletionStage[] { voidFuture() };
			final MutableInteger rows = new MutableInteger();
			return cleanUpCollectionTablesStage
					.thenCompose( v -> visitUnionTableReferences(
							suppliedPredicate,
							tableGroup,
							sqlExpressionResolver,
							executionContext,
							matchingIdSubQuerySpec,
							jdbcParameterBindings,
							deleteFromNonRootStages,
							rows
					) )
					.thenApply( o -> rows.get() );
		}
		else {
			final CompletionStage<Void>[] deleteFromNonRootStages = new CompletionStage[] { voidFuture() };

			entityDescriptor.visitConstraintOrderedTables(
					(tableExpression, tableKeyColumnVisitationSupplier) -> {
						if ( !tableExpression.equals( rootTableName ) ) {
							final NamedTableReference tableReference = (NamedTableReference) tableGroup.getTableReference(
									tableGroup.getNavigablePath(),
									tableExpression,
									true,
									true
							);
							final QuerySpec idMatchingSubQuerySpec;
							// No need for a predicate if there is no supplied predicate i.e. this is a full cleanup
							idMatchingSubQuerySpec = suppliedPredicate == null ? null : matchingIdSubQuerySpec;
							CompletableFuture<Void> future = new CompletableFuture<>();
							deleteFromNonRootStages[0] = deleteFromNonRootStages[0]
									.thenCompose( v -> future );
							try {
								deleteFromNonRootTableWithoutIdTable(
										tableReference,
										tableKeyColumnVisitationSupplier,
										sqlExpressionResolver,
										tableGroup,
										idMatchingSubQuerySpec,
										jdbcParameterBindings,
										executionContext
								)
										.thenCompose( CompletionStages::voidFuture )
										.whenComplete( (unused, throwable) -> {
											if ( throwable == null ) {
												future.complete( unused );
											}
											else {
												future.completeExceptionally( throwable );
											}
										} );
							}
							catch (Throwable t) {
								future.completeExceptionally( t );
							}
						}
					}
			);

			return deleteFromNonRootStages[0]
					.thenCompose( v -> deleteFromRootTableWithoutIdTable(
							rootTableReference,
							suppliedPredicate,
							jdbcParameterBindings,
							executionContext
					) );
		}
	}

	private CompletionStage<Void> visitUnionTableReferences(
			Predicate suppliedPredicate,
			TableGroup tableGroup,
			SqlExpressionResolver sqlExpressionResolver,
			ExecutionContext executionContext,
			QuerySpec matchingIdSubQuerySpec,
			JdbcParameterBindings jdbcParameterBindings,
			CompletionStage<Void>[] deleteFromNonRootStages,
			MutableInteger rows) {
		entityDescriptor.visitConstraintOrderedTables(
				(tableExpression, tableKeyColumnVisitationSupplier) -> {
					final NamedTableReference tableReference = new NamedTableReference(
							tableExpression,
							tableGroup.getPrimaryTableReference().getIdentificationVariable()
					);
					final QuerySpec idMatchingSubQuerySpec;
					// No need for a predicate if there is no supplied predicate i.e. this is a full cleanup
					idMatchingSubQuerySpec = suppliedPredicate == null ? null : matchingIdSubQuerySpec;
					CompletableFuture<Void> future = new CompletableFuture<>();
					deleteFromNonRootStages[0] = deleteFromNonRootStages[0]
							.thenCompose( v -> future );
					deleteFromNonRootTableWithoutIdTable(
							tableReference,
							tableKeyColumnVisitationSupplier,
							sqlExpressionResolver,
							tableGroup,
							idMatchingSubQuerySpec,
							jdbcParameterBindings,
							executionContext
					)
							.thenAccept( rows::plus )
							.whenComplete( (unused, throwable) -> {
								if ( throwable == null ) {
									future.complete( unused );
								}
								else {
									future.completeExceptionally( throwable );
								}
							} );
				}
		);
		return deleteFromNonRootStages[0];
	}

	private CompletionStage<Integer> deleteFromRootTableWithoutIdTable(
			NamedTableReference rootTableReference,
			Predicate predicate,
			JdbcParameterBindings jdbcParameterBindings,
			ExecutionContext executionContext) {
		return executeSqlDelete(
				new DeleteStatement( rootTableReference, predicate ),
				jdbcParameterBindings,
				executionContext
		);
	}

	private CompletionStage<Integer> deleteFromNonRootTableWithoutIdTable(
			NamedTableReference targetTableReference,
			Supplier<Consumer<SelectableConsumer>> tableKeyColumnVisitationSupplier,
			SqlExpressionResolver sqlExpressionResolver,
			TableGroup rootTableGroup,
			QuerySpec matchingIdSubQuerySpec,
			JdbcParameterBindings jdbcParameterBindings,
			ExecutionContext executionContext) {
		assert targetTableReference != null;
		LOG.tracef( "deleteFromNonRootTable - %s", targetTableReference.getTableExpression() );

		final NamedTableReference deleteTableReference = new NamedTableReference(
				targetTableReference.getTableExpression(),
				DeleteStatement.DEFAULT_ALIAS,
				true
		);
		final Predicate tableDeletePredicate;
		if ( matchingIdSubQuerySpec == null ) {
			tableDeletePredicate = null;
		}
		else {
			/*
			 * delete from sub_table
			 * where sub_id in (
			 * 		select root_id from root_table
			 * 		where {predicate}
			 * )
			 */

			/*
			 * Create the `sub_id` reference as the LHS of the in-subquery predicate
			 */
			final List<ColumnReference> deletingTableColumnRefs = new ArrayList<>();
			tableKeyColumnVisitationSupplier.get().accept(
					(columnIndex, selection) -> {
						assert deleteTableReference.getTableReference( selection.getContainingTableExpression() ) != null;

						final Expression expression = sqlExpressionResolver.resolveSqlExpression(
								deleteTableReference,
								selection
						);

						deletingTableColumnRefs.add( (ColumnReference) expression );
					}
			);

			final Expression deletingTableColumnRefsExpression = deletingTableColumnRefs.size() == 1
					? deletingTableColumnRefs.get( 0 )
					: new SqlTuple( deletingTableColumnRefs, entityDescriptor.getIdentifierMapping() );

			tableDeletePredicate = new InSubQueryPredicate(
					deletingTableColumnRefsExpression,
					matchingIdSubQuerySpec,
					false
			);
		}

		final DeleteStatement sqlAstDelete = new DeleteStatement( deleteTableReference, tableDeletePredicate );
		return executeSqlDelete(
				sqlAstDelete,
				jdbcParameterBindings,
				executionContext
		).thenApply( rows -> {
			LOG.debugf( "deleteFromNonRootTable - `%s` : %s rows", targetTableReference, rows );
			return rows;
		} );
	}

	private static CompletionStage<Integer> executeSqlDelete(
			DeleteStatement sqlAst,
			JdbcParameterBindings jdbcParameterBindings,
			ExecutionContext executionContext) {
		final SessionFactoryImplementor factory = executionContext.getSession().getFactory();

		final JdbcServices jdbcServices = factory.getJdbcServices();

		final JdbcOperationQueryDelete jdbcDelete = jdbcServices.getJdbcEnvironment()
				.getSqlAstTranslatorFactory()
				.buildDeleteTranslator( factory, sqlAst )
				.translate( jdbcParameterBindings, executionContext.getQueryOptions() );

		return StandardReactiveJdbcMutationExecutor.INSTANCE
				.executeReactive(
						jdbcDelete,
						jdbcParameterBindings,
						sql -> executionContext.getSession()
								.getJdbcCoordinator()
								.getStatementPreparer()
								.prepareStatement( sql ),
						(integer, preparedStatement) -> {},
						executionContext
				);
	}

	private CompletionStage<Integer> executeWithIdTable(
			Predicate predicate,
			TableGroup deletingTableGroup,
			Map<SqmParameter<?>, List<List<JdbcParameter>>> restrictionSqmParameterResolutions,
			Map<SqmParameter<?>, MappingModelExpressible<?>> paramTypeResolutions,
			ExecutionContext executionContext) {
		final JdbcParameterBindings jdbcParameterBindings = SqmUtil.createJdbcParameterBindings(
				executionContext.getQueryParameterBindings(),
				domainParameterXref,
				SqmUtil.generateJdbcParamsXref(
						domainParameterXref,
						() -> restrictionSqmParameterResolutions
				),
				sessionFactory.getRuntimeMetamodels().getMappingMetamodel(),
				navigablePath -> deletingTableGroup,
				new SqmParameterMappingModelResolutionAccess() {
					@Override
					@SuppressWarnings("unchecked")
					public <T> MappingModelExpressible<T> getResolvedMappingModelType(SqmParameter<T> parameter) {
						return (MappingModelExpressible<T>) paramTypeResolutions.get( parameter );
					}
				},
				executionContext.getSession()
		);

		return ReactiveExecuteWithTemporaryTableHelper
				.performBeforeTemporaryTableUseActions( idTable, executionContext )
				.thenCompose( v -> executeUsingIdTable( predicate, executionContext, jdbcParameterBindings )
						.handle( CompletionStages::handle )
						.thenCompose( resultHandler -> ReactiveExecuteWithTemporaryTableHelper
								.performAfterTemporaryTableUseActions(
										idTable,
										sessionUidAccess,
										afterUseAction,
										executionContext
								)
								.thenCompose( resultHandler::getResultAsCompletionStage )
						)
				);
	}

	private CompletionStage<Integer> executeUsingIdTable(
			Predicate predicate,
			ExecutionContext executionContext,
			JdbcParameterBindings jdbcParameterBindings) {
		return ReactiveExecuteWithTemporaryTableHelper.saveMatchingIdsIntoIdTable(
				converter,
				predicate,
				idTable,
				sessionUidAccess,
				jdbcParameterBindings,
				executionContext )
				.thenCompose( rows -> {
					final QuerySpec idTableIdentifierSubQuery = createIdTableSelectQuerySpec(
							idTable,
							sessionUidAccess,
							entityDescriptor,
							executionContext
					);

					return ReactiveSqmMutationStrategyHelper.cleanUpCollectionTables(
							entityDescriptor,
							(tableReference, attributeMapping) -> {
								final ForeignKeyDescriptor fkDescriptor = attributeMapping.getKeyDescriptor();
								final QuerySpec idTableFkSubQuery = fkDescriptor.getTargetPart()
										.isEntityIdentifierMapping()
										? idTableIdentifierSubQuery
										: createIdTableSelectQuerySpec( idTable, fkDescriptor.getTargetPart(), sessionUidAccess, entityDescriptor, executionContext );
								return new InSubQueryPredicate(
										MappingModelCreationHelper.buildColumnReferenceExpression(
												new MutatingTableReferenceGroupWrapper(
														new NavigablePath( attributeMapping.getRootPathName() ),
														attributeMapping,
														(NamedTableReference) tableReference
												),
												fkDescriptor,
												null,
												sessionFactory
										),
										idTableFkSubQuery,
										false
								);

							},
							JdbcParameterBindings.NO_BINDINGS,
							executionContext
					).thenCompose( unused -> visitConstraintOrderedTables( idTableIdentifierSubQuery, executionContext )
							.thenApply( v -> rows ) );
				} );
	}

	private CompletionStage<Void> visitConstraintOrderedTables(QuerySpec idTableIdentifierSubQuery, ExecutionContext executionContext) {
		final CompletionStage<Integer>[] resultStage = new CompletionStage[]{ completedFuture( -1 ) };
		entityDescriptor.visitConstraintOrderedTables(
				(tableExpression, tableKeyColumnVisitationSupplier) -> {
					resultStage[0] = resultStage[0].thenCompose( ignore -> deleteFromTableUsingIdTable(
							tableExpression,
							tableKeyColumnVisitationSupplier,
							idTableIdentifierSubQuery,
							executionContext
					) );
				}
		);
		return resultStage[0]
				.thenCompose( CompletionStages::voidFuture );
	}

	private CompletionStage<Integer> deleteFromTableUsingIdTable(
			String tableExpression,
			Supplier<Consumer<SelectableConsumer>> tableKeyColumnVisitationSupplier,
			QuerySpec idTableSubQuery,
			ExecutionContext executionContext) {
		LOG.tracef( "deleteFromTableUsingIdTable - %s", tableExpression );

		final SessionFactoryImplementor factory = executionContext.getSession().getFactory();

		final TableKeyExpressionCollector keyColumnCollector = new TableKeyExpressionCollector( entityDescriptor );
		final NamedTableReference targetTable = new NamedTableReference(
				tableExpression,
				DeleteStatement.DEFAULT_ALIAS,
				true
		);

		tableKeyColumnVisitationSupplier.get().accept(
				(columnIndex, selection) -> {
					assert selection.getContainingTableExpression().equals( tableExpression );
					assert !selection.isFormula();
					assert selection.getCustomReadExpression() == null;
					assert selection.getCustomWriteExpression() == null;

					keyColumnCollector
							.apply( new ColumnReference( targetTable, selection ) );
				}
		);

		final InSubQueryPredicate predicate = new InSubQueryPredicate(
				keyColumnCollector.buildKeyExpression(),
				idTableSubQuery,
				false
		);

		return executeSqlDelete(
				new DeleteStatement( targetTable, predicate ),
				JdbcParameterBindings.NO_BINDINGS,
				executionContext
		);
	}

}
