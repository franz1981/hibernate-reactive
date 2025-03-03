/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.reactive.containers.DatabaseConfiguration.DBType.POSTGRESQL;
import static org.hibernate.reactive.testing.DatabaseSelectionRule.runOnlyFor;
import static org.hibernate.reactive.testing.ReactiveAssertions.assertThrown;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.stage.Stage;
import org.hibernate.reactive.stage.Stage.Session;
import org.hibernate.reactive.testing.DatabaseSelectionRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.unit.TestContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Checks that we throw the right exception when a session is shared between threads.
 * @see org.hibernate.reactive.common.InternalStateAssertions
 */
public class InternalStateAssertionsTest extends BaseReactiveTest {

	/**
	 * @see org.hibernate.reactive.logging.impl.Log#detectedUsedOfTheSessionOnTheWrongThread
	 */
	private static final String ERROR_CODE = "HR000069";

	private Object currentSession;

	// These tests will fail before touching the database, so there is no reason
	// to run them on all databases
	@Rule
	public DatabaseSelectionRule rule = runOnlyFor( POSTGRESQL );

	@Override
	protected Collection<Class<?>> annotatedEntities() {
		return List.of( Competition.class );
	}

	@After
	public void closeEverything(TestContext context) {
		test( context, closeSession( currentSession ) );
	}

	@Test
	public void testPersistWithStage(TestContext testContext) {
		CompletionStage<Stage.Session> sessionStage = getSessionFactory().openSession();
		currentSession = sessionStage;

		ThreadPerCommandExecutor executor = new ThreadPerCommandExecutor();

		test( testContext, assertThrown( IllegalStateException.class, sessionStage
				.thenComposeAsync( session -> session.persist( new Competition( "Cheese Rolling" ) ), executor ) )
				.thenAccept( InternalStateAssertionsTest::assertException )
		);
	}

	@Test
	public void testFindWithStage(TestContext testContext) {
		CompletionStage<Stage.Session> sessionStage = getSessionFactory().openSession();
		currentSession = sessionStage;

		ThreadPerCommandExecutor executor = new ThreadPerCommandExecutor();

		test( testContext, assertThrown( IllegalStateException.class, sessionStage
				.thenComposeAsync( InternalStateAssertionsTest::findChessBoxing, executor ) )
				.thenAccept( InternalStateAssertionsTest::assertException )
		);
	}

	private static CompletionStage<Competition> findChessBoxing(Session session) {
		return session.find( Competition.class, "Chess boxing" );
	}

	@Test
	public void testOnPersistWithMutiny(TestContext testContext) {
		Uni<Mutiny.Session> sessionUni = getMutinySessionFactory().openSession();
		currentSession = sessionUni;

		ThreadPerCommandExecutor executor = new ThreadPerCommandExecutor();

		test( testContext, assertThrown( IllegalStateException.class, sessionUni
				.call( session -> session
						.persist( new Competition( "Cheese Rolling" ) )
						.runSubscriptionOn( executor ) ) )
				.invoke( InternalStateAssertionsTest::assertException )
		);
	}

	@Test
	public void testFindWithMutiny(TestContext testContext) {
		Uni<Mutiny.Session> sessionUni = getMutinySessionFactory().openSession();
		currentSession = sessionUni;

		ThreadPerCommandExecutor executor = new ThreadPerCommandExecutor();

		test( testContext, assertThrown( IllegalStateException.class, sessionUni
				.chain( session -> session
						.find( Competition.class, "Chess boxing" )
						.runSubscriptionOn( executor ) ) )
				.invoke( InternalStateAssertionsTest::assertException )
		);
	}

	private static void assertException(Throwable t) {
		assertThat( t.getMessage() ).startsWith( ERROR_CODE );
	}

	/**
	 * Run a task in a thread that's different from the "Test worker"
	 */
	private static class ThreadPerCommandExecutor implements Executor {
		@Override
		public void execute(Runnable command) {
			new Thread( command, InternalStateAssertionsTest.class.getName() + "-thread" ).start();
		}
	}

	@Entity(name = "Competition")
	static class Competition {
		@Id
		String name;

		public Competition() {
		}

		public Competition(String name) {
			this.name = name;
		}
	}
}
