/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive;

import org.hibernate.cfg.Configuration;
import org.hibernate.reactive.containers.DatabaseConfiguration;
import org.hibernate.reactive.provider.Settings;

import org.junit.Test;

import io.vertx.ext.unit.TestContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Check that the right exception is thrown when there is an error with the credentials.
 * <p>
 *     Similar to {@link org.hibernate.reactive.configuration.ReactiveConnectionPoolTest} but at the session level.
 *     Note that the wrong credentials are also used for the schema generation but exceptions are ignored during
 *     schema generation. You might just see some warnings in the log.
 * </p>
 */
public class WrongCredentialsTest extends BaseReactiveTest {

	@Override
	protected Configuration constructConfiguration() {
		Configuration configuration = super.constructConfiguration();
		configuration.addAnnotatedClass( Artist.class );
		configuration.setProperty( Settings.USER, DatabaseConfiguration.USERNAME );
		configuration.setProperty( Settings.PASS, "BogusBogus" );
		return configuration;
	}

	@Override
	public void before(TestContext context) {
		// We need to postpone the creation of the factory so that we can check the exception
	}

	@Test
	public void testWithTransaction(TestContext context) {
		test( context, setupSessionFactory( this::constructConfiguration )
				.handle( (v, e) -> e )
				.thenAccept( WrongCredentialsTest::assertException )
		);
	}


	private static void assertException(Throwable throwable) {
		assertThat( throwable ).as( "We were expecting an exception" ).isNotNull();
		assertThat( throwable.getMessage().toLowerCase() )
				.containsAnyOf(
						"password authentication failed",
						"login failed",
						"access denied",
						"invalid credentials",
						// Oracle invalid username/password code
						"ORA-01017".toLowerCase()
				);
	}

	@Entity
	static class Artist {
		@Id
		String name;

		public Artist() {
		}

		public Artist(String name) {
			this.name = name;
		}
	}
}
