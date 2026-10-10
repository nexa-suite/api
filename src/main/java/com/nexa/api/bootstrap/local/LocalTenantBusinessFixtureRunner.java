package com.nexa.api.bootstrap.local;

import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Explicit one-Tenant CLI entry point for reviewed, local BC02/03/05 fixture projections. */
@Component
@Profile("local-fixtures")
@ConditionalOnProperty(prefix = "nexa.local-demo-fixtures", name = "enabled", havingValue = "true")
public final class LocalTenantBusinessFixtureRunner implements ApplicationRunner {
	private final Environment environment;
	private final JdbcTemplate centralJdbc;
	private final ResolveCurrentAccessContextUseCase accessContexts;
	private final TenantBusinessDatabaseRouter tenantRouter;
	private final CatalogPersistenceBootstrap catalogSeed;
	private final CatalogSkuPersistenceBootstrap skuSeed;
	private final LocalTenantBusinessFixtureSeeder businessSeed;

	public LocalTenantBusinessFixtureRunner(Environment environment, JdbcTemplate centralJdbc,
			ResolveCurrentAccessContextUseCase accessContexts, TenantBusinessDatabaseRouter tenantRouter,
			CatalogPersistenceBootstrap catalogSeed, CatalogSkuPersistenceBootstrap skuSeed,
			LocalTenantBusinessFixtureSeeder businessSeed) {
		this.environment = environment;
		this.centralJdbc = centralJdbc;
		this.accessContexts = accessContexts;
		this.tenantRouter = tenantRouter;
		this.catalogSeed = catalogSeed;
		this.skuSeed = skuSeed;
		this.businessSeed = businessSeed;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		UUID tenantId = requiredUuid("nexa.local-demo-fixtures.tenant-id");
		UUID workspaceId = requiredUuid("nexa.local-demo-fixtures.workspace-id");
		UUID buyerUserId = requiredUuid("nexa.local-demo-fixtures.buyer-user-id");
		UUID buyerMembershipId = requiredUuid("nexa.local-demo-fixtures.buyer-membership-id");
		String buyerClientCode = businessSeed.validateBuyerClientCode(
				environment.getProperty("nexa.local-demo-fixtures.buyer-client-code"));
		CurrentAccessContext buyer = centralBuyerPreflight(tenantId, workspaceId, buyerUserId, buyerMembershipId);

		RlsRequestScope.set(tenantId, workspaceId);
		try {
			tenantRouter.inTransaction(buyer, targetJdbc -> {
				catalogSeed.importExactTenantWorkspace(targetJdbc, tenantId, workspaceId);
				skuSeed.reconcileExactTenantWorkspace(targetJdbc, tenantId, workspaceId);
				businessSeed.seed(targetJdbc, tenantId, workspaceId, buyerMembershipId, buyerClientCode);
				return Boolean.TRUE;
			});
		} finally {
			RlsRequestScope.clear();
		}
	}

	private CurrentAccessContext centralBuyerPreflight(UUID tenantId, UUID workspaceId, UUID userId,
			UUID membershipId) {
		RlsRequestScope.set(tenantId, workspaceId);
		try {
			Integer matches = centralJdbc.queryForObject("select count(*) from tenant_management.workspace_membership m "
					+ "join tenant_management.workspace w on w.id=m.workspace_id and w.tenant_id=? "
					+ "join tenant_management.tenant t on t.id=w.tenant_id "
					+ "where m.id=? and m.user_id=? and m.workspace_id=? and m.membership_type='BUYER' "
					+ "and m.status='ACTIVE' and w.status='ACTIVE' and t.status='ACTIVE'",
				Integer.class, tenantId, membershipId, userId, workspaceId);
			if (matches == null || matches != 1) {
				throw new IllegalStateException("Exact active Buyer membership was not found in central identity");
			}
			CurrentAccessContext context = accessContexts.resolve(new CurrentAccessRequest(new UserId(userId),
					new TenantId(tenantId), new WorkspaceId(workspaceId), Surface.PORTAL));
			if (!membershipId.equals(context.membershipId().value()) || !tenantId.equals(context.tenantId().value())
					|| !workspaceId.equals(context.workspaceId().value()) || !context.hasRole(MembershipRole.BUYER)) {
				throw new IllegalStateException("Resolved Buyer access did not match the requested Tenant scope");
			}
			return context;
		} finally {
			RlsRequestScope.clear();
		}
	}

	private UUID requiredUuid(String key) {
		String value = environment.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required option: " + key);
		}
		try {
			return UUID.fromString(value.trim());
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid UUID option: " + key);
		}
	}
}
