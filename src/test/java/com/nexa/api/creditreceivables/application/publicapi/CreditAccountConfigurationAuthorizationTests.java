package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditAccountConfigurationAuthorizationTests {
    @Test
    void bomConfigurationRoleHasOnlyDedicatedConfigurationCapability() {
        CurrentAccessContext context = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER,
                EffectiveAuthorization.fixed(Set.of(MembershipRole.BUSINESS_OPERATIONS_MANAGER), 0));

        assertThatCode(() -> CreditAccountConfigurationAuthorization.require(context)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(context.allows(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE)).isTrue();
        org.assertj.core.api.Assertions.assertThat(context.allows(PermissionKey.CLIENT_CREDIT_MANAGE)).isFalse();
    }

    @Test
    void broadCreditManagementPermissionDoesNotGrantConfigurationAuthority() {
        TenantId tenant = TenantId.random();
        WorkspaceId workspace = WorkspaceId.random();
        RoleDefinition financialRole = RoleDefinition.custom(tenant, workspace, "credit-adjustments",
                "Credit adjustments", "Existing financial adjustment permission",
                Set.of(PermissionKey.CLIENT_CREDIT_MANAGE), UserId.random(), Instant.parse("2026-10-09T00:00:00Z"));
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(financialRole),
                Set.of(MembershipRole.SALES), 0);
        CurrentAccessContext context = context(MembershipRole.SALES, authorization);

        assertThatThrownBy(() -> CreditAccountConfigurationAuthorization.require(context))
                .isInstanceOf(AccessPolicyViolation.class);
    }

    @Test
    void permissionWithoutAnAuthorizedRoleDoesNotGrantConfigurationAuthority() {
        TenantId tenant = TenantId.random();
        WorkspaceId workspace = WorkspaceId.random();
        RoleDefinition configurationRole = RoleDefinition.custom(tenant, workspace, "credit-policy-config",
                "Credit policy configuration", "Configuration capability without an authorized system role",
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE), UserId.random(),
                Instant.parse("2026-10-09T00:00:00Z"));
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(configurationRole),
                Set.of(MembershipRole.SALES), 0);
        CurrentAccessContext context = context(MembershipRole.SALES, authorization);

        assertThatThrownBy(() -> CreditAccountConfigurationAuthorization.require(context))
                .isInstanceOf(AccessPolicyViolation.class);
    }

    @Test
    void companyOwnerMayUseNarrowConfigurationCapabilityOnPlatformSurface() {
        CurrentAccessContext context = context(MembershipRole.COMPANY_OWNER,
                EffectiveAuthorization.fixed(Set.of(MembershipRole.COMPANY_OWNER), 0));

        assertThatCode(() -> CreditAccountConfigurationAuthorization.require(context)).doesNotThrowAnyException();
    }

    private static CurrentAccessContext context(MembershipRole membershipRole,
            EffectiveAuthorization authorization) {
        TenantId tenant = TenantId.random();
        WorkspaceId workspace = WorkspaceId.random();
        Membership membership = new Membership(MembershipId.random(), UserId.random(), tenant, workspace,
                Set.of(membershipRole), MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), Surface.PLATFORM);
    }
}
