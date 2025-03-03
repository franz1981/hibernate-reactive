/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.hibernate.reactive.testing.DatabaseSelectionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import io.vertx.ext.unit.TestContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import static org.hibernate.reactive.containers.DatabaseConfiguration.DBType.DB2;
import static org.hibernate.reactive.testing.DatabaseSelectionRule.skipTestsFor;


/**
 * Tests queries using named parameters like ":name",
 * as defined by the JPA specification, along with limit parameters
 */
public class HQLQueryParameterNamedLimitTest extends BaseReactiveTest {

	// Db2: java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.Long
	@Rule
	public DatabaseSelectionRule skip = skipTestsFor( DB2 );

	Flour spelt = new Flour( 1, "Spelt", "An ancient grain, is a hexaploid species of wheat.", "Wheat flour" );
	Flour rye = new Flour( 2, "Rye", "Used to bake the traditional sourdough breads of Germany.", "Wheat flour" );
	Flour almond = new Flour( 3, "Almond", "made from ground almonds.", "Gluten free" );

	@Override
	protected Collection<Class<?>> annotatedEntities() {
		return List.of( Flour.class );
	}

	@Before
	public void populateDb(TestContext context) {
		test( context, openSession()
				.thenCompose( s -> s.persist( spelt )
				.thenCompose( v -> s.persist( rye ) )
				.thenCompose( v -> s.persist( almond ) )
				.thenCompose( v -> s.flush() ) )
		);
	}

	@Test
	public void testNoResults(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour where id = :id" ).setMaxResults( 0 )
										.setParameter( "id", rye.getId() )
										.getResultList()
										.thenAccept( list -> context.assertEquals( 0, list.size() ) )
						)
		);
	}

	@Test
	public void testFirstResultNoResults(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour" )
										.setMaxResults( 0 )
										.setFirstResult( 1 )
										.getResultList()
										.thenAccept( list -> context.assertEquals( 0, list.size() ) )
						)
		);
	}

	/**
	 * The MSSQL dialect generates a different query when there are no order-by clause and no filters
	 */
	@Test
	public void testFirstResultWithoutOrderBy(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s -> s.createQuery( "from Flour where id > :id" )
								.setParameter( "id", 1 )
								.setMaxResults( 1 )
								.setFirstResult( 1 )
								.getResultList()
								.thenAccept( list -> context.assertEquals( 1, list.size() ) )
						)
		);
	}

	@Test
	public void testFirstResultSingleResult(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour where name != :name order by id" )
										.setParameter( "name", spelt.getName() )
										.setFirstResult( 1 )
										.getSingleResult()
										.thenAccept( result -> context.assertEquals( almond, result ) )
						)
		);
	}

	@Test
	public void testFirstResultMultipleResults(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour order by id" )
										.setFirstResult( 1 )
										.getResultList()
										.thenAccept( results -> {
											context.assertEquals( 2, results.size() );
											context.assertEquals( rye, results.get( 0 ) );
											context.assertEquals( almond, results.get( 1 ) );
										} )
						)
		);
	}

	@Test
	public void testFirstResultMaxResultsSingleResult(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour order by id" )
										.setFirstResult( 1 )
										.setMaxResults( 1 )
										.getSingleResult()
										.thenAccept( result -> {
											context.assertEquals( rye, result );
										} )
						)
		);
	}

	/**
	 * Some databases (see MSSQL) generate a different SQL query when the first result is set to 0
	 */
	@Test
	public void testFirstResultZeroAndMaxResults(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
											  s.createQuery( "from Flour where name = :name order by id" )
													  .setParameter( "name", almond.getName() )
													  .setFirstResult( 0 )
													  .setMaxResults( 10 )
													  .getResultList()
													  .thenAccept( results -> {
														  context.assertEquals( 1, results.size() );
														  context.assertEquals( almond, results.get( 0 ) );
													  } )
						)
		);
	}

	/**
	 * Some databases (see MSSQL) generate a different SQL query to limit the results when
	 * the HQL query has no order by clause
	 */
	@Test
	public void testFirstResultZeroAndMaxResultsWithoutOrder(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
											  s.createQuery( "from Flour where name = :name" )
													  .setParameter( "name", almond.getName() )
													  .setFirstResult( 0 )
													  .setMaxResults( 10 )
													  .getResultList()
													  .thenAccept( results -> {
														  context.assertEquals( 1, results.size() );
														  context.assertEquals( almond, results.get( 0 ) );
													  } )
						)
		);
	}

	@Test
	public void testFirstResultMaxResultsMultipleResults(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour order by id" )
										.setFirstResult( 1 )
										.setMaxResults( 2 )
										.getResultList()
										.thenAccept( results -> {
											context.assertEquals( 2, results.size() );
											context.assertEquals( rye, results.get( 0 ) );
											context.assertEquals( almond, results.get( 1 ) );
										} )
						)
		);
	}

	@Test
	public void testFirstResultMaxResultsExtra(TestContext context) {
		test(
				context,
				openSession()
						.thenCompose( s ->
								s.createQuery( "from Flour order by id" )
										.setFirstResult( 1 )
										.setMaxResults( 3 )
										.getResultList()
										.thenAccept( results -> {
											context.assertEquals( 2, results.size() );
											context.assertEquals( rye, results.get( 0 ) );
											context.assertEquals( almond, results.get( 1 ) );
										} )
						)
		);
	}

	@Entity(name = "Flour")
	@Table(name = "Flour")
	public static class Flour {
		@Id
		private Integer id;
		private String name;
		private String description;
		private String type;

		public Flour() {
		}

		public Flour(Integer id, String name, String description, String type) {
			this.id = id;
			this.name = name;
			this.description = description;
			this.type = type;
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		@Override
		public String toString() {
			return name;
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( o == null || getClass() != o.getClass() ) {
				return false;
			}
			Flour flour = (Flour) o;
			return Objects.equals( name, flour.name ) &&
					Objects.equals( description, flour.description ) &&
					Objects.equals( type, flour.type );
		}

		@Override
		public int hashCode() {
			return Objects.hash( name, description, type );
		}
	}
}
