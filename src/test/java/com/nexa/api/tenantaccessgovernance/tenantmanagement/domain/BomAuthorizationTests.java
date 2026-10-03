package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.*;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class BomAuthorizationTests {
    @Test void bomCoordinatesWithoutUnderlyingDomainAuthorityOrLegacyAliases() {
        EffectiveAuthorization authority=EffectiveAuthorization.fixed(Set.of(MembershipRole.BUSINESS_OPERATIONS_MANAGER),3);
        assertThat(authority.permissionCodes()).containsExactlyInAnyOrder(
                "delivery.exception.read","delivery.exception.coordinate","notification.read","notification.manage_preferences");
        assertThat(authority.allows(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE)).isFalse();
        assertThat(authority.allows(PermissionKey.INVENTORY_RELEASE)).isFalse();
        assertThat(authority.allows(PermissionKey.DISPATCH_COMPLETE)).isFalse();
        assertThat(authority.allowsLegacy(Permission.LOGISTICS_WRITE)).isFalse();
        assertThat(authority.allowsSurface(Surface.PLATFORM)).isTrue();
        assertThat(authority.allowsSurface(Surface.PORTAL)).isFalse();
        assertThat(PermissionPolicy.permissionsFor(MembershipRole.BUSINESS_OPERATIONS_MANAGER)).isEmpty();
    }

    @Test void bomIsExplicitAssignableSystemTemplateWithStableIdentity() {
        assertThat(MembershipRole.from("business operations manager")).isEqualTo(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        assertThat(RoleCatalog.internalAssignableRoles()).contains(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        assertThat(MembershipRole.BUSINESS_OPERATIONS_MANAGER.definition().id().toString()).isEqualTo("007b12ab-81dd-307a-ac41-9bc9888924ed");
        assertThat(MembershipRole.BUSINESS_OPERATIONS_MANAGER.definition().type()).isEqualTo(RoleDefinitionType.SYSTEM_TEMPLATE);
    }

    @Test void dispositionIsExplicitAndNeverAReportOrCoordinationDefault() {
        for (MembershipRole role:MembershipRole.values())
            assertThat(PermissionCatalog.forBuiltInRole(role)).doesNotContain(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE);
        RoleDefinition role=RoleDefinition.custom(TenantId.random(),null,"cold_chain_disposition","Cold-chain disposition","Explicit goods authority",
                Set.of(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE),UserId.random(),Instant.EPOCH);
        EffectiveAuthorization authority=EffectiveAuthorization.canonical(Set.of(role),4);
        assertThat(authority.allows(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE)).isTrue();
        assertThat(authority.allows(PermissionKey.DELIVERY_EXCEPTION_COORDINATE)).isFalse();
        assertThat(authority.allowsLegacy(Permission.LOGISTICS_WRITE)).isFalse();
    }
}
