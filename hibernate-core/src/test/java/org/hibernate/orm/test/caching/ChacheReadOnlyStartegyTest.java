package org.hibernate.orm.test.caching;

import java.util.List;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.cfg.AvailableSettings;

import org.hibernate.testing.orm.junit.EntityManagerFactoryScope;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.Jpa;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JiraKey("HHH-17997")
@Jpa(
		annotatedClasses = {
				ChacheReadOnlyStartegyTest.TestEntity.class
		},
		integrationSettings = {
				@Setting(name = AvailableSettings.USE_SECOND_LEVEL_CACHE, value = "true"),
				@Setting(name = AvailableSettings.USE_QUERY_CACHE, value = "false"),
		}
)
public class ChacheReadOnlyStartegyTest {

	@AfterEach
	public void tearDown(EntityManagerFactoryScope scope) {
		scope.inTransaction(
				entityManager ->
						entityManager.createQuery( "delete TestEntity" ).executeUpdate()
		);
	}

	@Test
	public void testPersistThenClearAndQuery(EntityManagerFactoryScope scope) {
		final long testEntityId = 1l;

		scope.inTransaction(
				entityManager -> {
					TestEntity entity = new TestEntity( testEntityId, "test" );
					entityManager.persist( entity );
					entityManager.flush();
					entityManager.clear();
					List<TestEntity> results = entityManager.createQuery(
							"select t from TestEntity t where t.id = :id",
							TestEntity.class
					).setParameter( "id", testEntityId ).getResultList();

					assertThat( results.size() ).isEqualTo( 1 );
				}
		);

		scope.inTransaction(
				entityManager -> {
					assertTrue( scope.getEntityManagerFactory()
										.getCache()
										.contains( TestEntity.class, testEntityId ) );
				}
		);
	}

	@Test
	public void testPersistThenClearAndQueryWithRollback(EntityManagerFactoryScope scope) {
		final long testEntityId = 1l;

		scope.inEntityManager(
				entityManager -> {
					entityManager.getTransaction().begin();
					try {
						TestEntity entity = new TestEntity( testEntityId, "test" );
						entityManager.persist( entity );
						entityManager.flush();
						entityManager.clear();
						List<TestEntity> results = entityManager.createQuery(
								"select t from TestEntity t where t.id = :id",
								TestEntity.class
						).setParameter( "id", testEntityId ).getResultList();

						assertThat( results.size() ).isEqualTo( 1 );
					}
					finally {
						entityManager.getTransaction().rollback();
					}
				}
		);

		scope.inTransaction(
				entityManager -> {
					assertFalse( scope.getEntityManagerFactory()
										 .getCache()
										 .contains( TestEntity.class, testEntityId ) );
					List<TestEntity> results = entityManager.createQuery(
							"select t from TestEntity t where t.id = :id",
							TestEntity.class
					).setParameter( "id", testEntityId ).getResultList();

					assertThat( results.size() ).isEqualTo( 0 );
				}
		);
	}

	@Entity(name = "TestEntity")
	@Cacheable
	@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
	public static class TestEntity {
		@Id
		private Long id;

		private String name;

		public TestEntity() {
		}

		public TestEntity(Long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

}
