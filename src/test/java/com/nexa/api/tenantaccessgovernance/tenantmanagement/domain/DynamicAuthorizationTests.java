package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.AssignableRolePolicy;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinitionType;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicAuthorizationTests {
	@Test
	void catalogIsTypedClosedAndHasTheRequiredRoleEnvelope() {
		assertThat(PermissionCatalog.require("catalog.read")).isEqualTo(PermissionKey.CATALOG_READ);
		assertThat(PermissionCatalog.all()).contains(PermissionKey.TENANT_ROLE_ASSIGN_RESERVED,
				PermissionKey.DISPATCH_COMPLETE, PermissionKey.ORDER_EXPORT_READ);
		assertThat(PermissionCatalog.require("buyer.receipt.evidence.attach"))
				.isEqualTo(PermissionKey.BUYER_RECEIPT_EVIDENCE_ATTACH);
		assertThat(PermissionCatalog.forBuiltInRole(MembershipRole.BUYER))
				.contains(PermissionKey.BUYER_RECEIPT_EVIDENCE_ATTACH)
				.doesNotContain(PermissionKey.DOCUMENT_UPLOAD);
		assertThat(PermissionCatalog.companyOwnerAssignableEnvelope())
				.doesNotContain(PermissionKey.TENANT_SECURITY_MANAGE, PermissionKey.TENANT_ROLE_MANAGE,
						PermissionKey.TENANT_ROLE_ASSIGN_RESERVED, PermissionKey.TENANT_AUDIT_READ);
		assertThatThrownBy(() -> PermissionCatalog.require("tenant.permission.arbitrary"))
				.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> RoleDefinition.custom(TenantId.random(), null, "tenant_admin", "Impostor", "",
				Set.of(PermissionKey.CATALOG_READ), UserId.random(), Instant.EPOCH)).isInstanceOf(RuntimeException.class);
	}

	@Test
	void multiRoleAuthorizationIsAUnionAndPreservesCanonicalRoleIds() {
		EffectiveAuthorization authorization = EffectiveAuthorization.fixed(
				Set.of(MembershipRole.SALES, MembershipRole.WAREHOUSE), 11);

		assertThat(authorization.roleCodes()).containsExactlyInAnyOrder("SALES", "WAREHOUSE");
		assertThat(authorization.roleDefinitionIds()).contains(
				RoleDefinitionId.system("SALES").toString(), RoleDefinitionId.system("WAREHOUSE").toString());
		assertThat(authorization).satisfies(value -> {
			assertThat(value.allows(PermissionKey.SALES_ORDER_CREATE_MANUAL)).isTrue();
			assertThat(value.allows(PermissionKey.INVENTORY_RECEIVE)).isTrue();
			assertThat(value.allowsSurface(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface.PLATFORM)).isTrue();
			assertThat(value.allowsSurface(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface.PORTAL)).isFalse();
		});
	}

	@Test
	void tenantAdministratorPresetCanReadCatalogAndClientsWithoutBusinessWritePermissions() {
		EffectiveAuthorization authorization = EffectiveAuthorization.fixed(Set.of(MembershipRole.TENANT_ADMIN), 11);

		assertThat(authorization.allows(PermissionKey.CATALOG_READ)).isTrue();
		assertThat(authorization.allows(PermissionKey.CLIENT_READ)).isTrue();
		assertThat(authorization.allows(PermissionKey.CATALOG_PRODUCT_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CATALOG_TAXONOMY_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CATALOG_PRICE_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CATALOG_PROMOTION_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_ADDRESS_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_COMMERCIAL_TERMS_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_CREDIT_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE)).isFalse();
	}

	@Test
	void salesPresetRetainsItsPersistedCommercialTermsAuthorityWithoutCreditAuthority() {
		EffectiveAuthorization authorization = EffectiveAuthorization.fixed(Set.of(MembershipRole.SALES), 11);

		assertThat(authorization.allows(PermissionKey.CLIENT_COMMERCIAL_TERMS_MANAGE)).isTrue();
		assertThat(authorization.allows(PermissionKey.CLIENT_CREDIT_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE)).isFalse();
	}

	@Test
	void logisticsPresetHasWarehouseReadButNoWarehouseObjectGrantOrInventoryWrites() {
		EffectiveAuthorization authorization = EffectiveAuthorization.fixed(Set.of(MembershipRole.LOGISTICS), 11);

		assertThat(authorization.allows(PermissionKey.WAREHOUSE_READ)).isTrue();
		assertThat(authorization.allows(PermissionKey.WAREHOUSE_LOCATION_MANAGE)).isFalse();
		assertThat(authorization.allows(PermissionKey.INVENTORY_RECEIVE)).isFalse();
		assertThat(authorization.allows(PermissionKey.INVENTORY_ADJUST)).isFalse();
		assertThat(authorization.allows(PermissionKey.INVENTORY_RESERVE)).isFalse();
	}

	@Test
	void dispatchCompleteRetainsTheExistingLogisticsWriteCompatibilityAlias() {
		EffectiveAuthorization logistics = EffectiveAuthorization.fixed(Set.of(MembershipRole.LOGISTICS), 11);
		EffectiveAuthorization warehouse = EffectiveAuthorization.fixed(Set.of(MembershipRole.WAREHOUSE), 11);

		assertThat(logistics.allows(PermissionKey.DISPATCH_COMPLETE)).isTrue();
		assertThat(logistics.allowsLegacy(Permission.LOGISTICS_WRITE)).isTrue();
		assertThat(warehouse.allows(PermissionKey.DISPATCH_COMPLETE)).isFalse();
	}

	@Test
	void roleDefinitionsClassifySystemRolesAndRejectStaleOrUnsafeChanges() {
		Instant now = Instant.parse("2026-08-02T00:00:00Z");
		RoleDefinition reserved = RoleDefinition.systemReserved(MembershipRole.TENANT_ADMIN, now);
		RoleDefinition template = RoleDefinition.systemTemplate(MembershipRole.SALES, now);
		RoleDefinition custom = RoleDefinition.custom(TenantId.random(), WorkspaceId.random(), "dispatch.viewer",
				"Dispatch viewer", "Read-only dispatch", Set.of(PermissionKey.DISPATCH_READ), UserId.random(), now);

		assertThat(reserved.type()).isEqualTo(RoleDefinitionType.SYSTEM_RESERVED);
		assertThat(template.type()).isEqualTo(RoleDefinitionType.SYSTEM_TEMPLATE);
		assertThat(custom.type()).isEqualTo(RoleDefinitionType.CUSTOM);
		assertThatThrownBy(() -> reserved.update("changed", "", Set.of(PermissionKey.TENANT_ROLE_READ), 0, now))
				.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> custom.update("changed", "", Set.of(PermissionKey.DISPATCH_READ), 99, now))
				.isInstanceOf(RoleDefinition.RoleDefinitionConcurrencyException.class);
		assertThatThrownBy(() -> custom.deactivate(0, 1, now))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void companyOwnerCannotAssignReservedOrTechnicalPermissions() {
		RoleDefinition reserved = RoleDefinition.systemReserved(MembershipRole.TENANT_ADMIN, Instant.EPOCH);
		RoleDefinition safe = RoleDefinition.custom(TenantId.random(), null, "sales.viewer", "Sales viewer", "",
				Set.of(PermissionKey.SALES_DASHBOARD_READ), UserId.random(), Instant.EPOCH);

		assertThatThrownBy(() -> AssignableRolePolicy.requireCanAssign(Set.of("COMPANY_OWNER"), Set.of(), reserved))
				.isInstanceOf(RuntimeException.class);
		AssignableRolePolicy.requireCanAssign(Set.of("COMPANY_OWNER"), Set.of(), safe);
		assertThatThrownBy(() -> AssignableRolePolicy.requireWithinAssignableEnvelope(Set.of("COMPANY_OWNER"),
				Set.of(PermissionKey.TENANT_SECURITY_MANAGE))).isInstanceOf(RuntimeException.class);
	}
}
