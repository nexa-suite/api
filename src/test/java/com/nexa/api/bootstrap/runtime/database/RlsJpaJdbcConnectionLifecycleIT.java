package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies central JPA and JDBC work share and release one pooled connection. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class RlsJpaJdbcConnectionLifecycleIT extends PostgresIntegrationSupport {
	@jakarta.annotation.Resource
	private PlatformTransactionManager transactionManager;

	@jakarta.annotation.Resource
	private jakarta.persistence.EntityManagerFactory entityManagerFactory;

	@jakarta.annotation.Resource
	private VerifiedMembershipResolutionPort verifiedMembershipResolution;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void jpaAndJdbcShareOnePoolLeaseAndReturnItAfterTransaction() throws java.sql.SQLException {
		assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
		var jpaTransactionManager = (JpaTransactionManager) transactionManager;
		var entityManagerFactoryDataSource = ((EntityManagerFactoryInfo) entityManagerFactory).getDataSource();
		var transactionDataSource = jpaTransactionManager.getDataSource();
		var jdbcDataSource = jdbc.getDataSource();

		assertThat(entityManagerFactoryDataSource).isSameAs(transactionDataSource);
		assertThat(jdbcDataSource).isSameAs(transactionDataSource);

		HikariDataSource pool = jdbcDataSource.unwrap(HikariDataSource.class);
		int activeBefore = pool.getHikariPoolMXBean().getActiveConnections();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.createNativeQuery("select 1").getSingleResult();
			int activeAfterJpa = pool.getHikariPoolMXBean().getActiveConnections();
			Object transactionBound = TransactionSynchronizationManager.getResource(transactionDataSource);
			Object jdbcBound = TransactionSynchronizationManager.getResource(jdbcDataSource);
			assertThat(transactionBound).isNotNull();
			assertThat(jdbcBound).isSameAs(transactionBound);

			jdbc.queryForObject("select 1", Integer.class);
			assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(activeAfterJpa);
		});
		assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(activeBefore);
	}

	@Test
	void repeatedVerifiedMembershipResolutionReturnsConnectionsToBaseline() throws java.sql.SQLException {
		String userId = jdbc.queryForObject("select m.user_id::text from tenant_management.workspace_membership m "
				+ "join iam.user_account u on u.id=m.user_id "
				+ "where u.normalized_email=? and m.workspace_id=(select id from tenant_management.workspace where slug=?)",
				String.class, OWNER_EMAIL, WORKSPACE_SLUG);
		var tenant = new TenantId(tenantId());
		var workspace = new WorkspaceId(workspaceId());
		var user = new UserId(userId);
		HikariDataSource pool = jdbc.getDataSource().unwrap(HikariDataSource.class);
		int activeBefore = pool.getHikariPoolMXBean().getActiveConnections();

		for (int attempt = 0; attempt < 12; attempt++) {
			assertThat(verifiedMembershipResolution.resolve(user, tenant, workspace)).isPresent();
			assertThat(pool.getHikariPoolMXBean().getActiveConnections())
					.as("active central connections after membership resolution %s".formatted(attempt + 1))
					.isEqualTo(activeBefore);
		}
	}
}
