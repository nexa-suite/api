package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.*;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.*;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyerCreditExposureTests {
    @Test void resolvesAccountOnlyFromVerifiedBuyerMembershipAndPreservesCreditFacts() {
        var context = context(MembershipRole.BUYER, Surface.PORTAL);
        var accounts = mock(CustomerAccountQuery.class);
        var exposures = mock(CreditExposureQuery.class);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        when(accounts.findBuyerReference(tenant, workspace, context.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference("current-account", "ACTIVE")));
        when(exposures.find(tenant, workspace, "current-account", "PEN"))
                .thenReturn(new CreditExposureQuery.CreditExposureSnapshot("PEN", new BigDecimal("1000.05"),
                        new BigDecimal("20.01"), new BigDecimal("50.02"), new BigDecimal("30.01"), true));
        var result = new CreditExposureApplicationService(accounts, exposures).readBuyer(context, "pen");
        assertThat(result.clientAccountId()).isEqualTo("current-account");
        assertThat(result.availableCredit()).isEqualByComparingTo("900.01");
        verify(accounts).findBuyerReference(tenant, workspace, context.membershipId().toString());
        verifyNoMoreInteractions(accounts);
    }
    @Test void missingOrInactiveRelationshipCannotReadExposure() {
        var context = context(MembershipRole.BUYER, Surface.PORTAL);
        for (var relation : java.util.List.of(Optional.<CustomerAccountReference>empty(), Optional.of(new CustomerAccountReference("account", "SUSPENDED")))) {
            var accounts = mock(CustomerAccountQuery.class); var exposures = mock(CreditExposureQuery.class);
            when(accounts.findBuyerReference(anyString(), anyString(), anyString())).thenReturn(relation);
            assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, exposures).readBuyer(context, "PEN"))
                    .hasMessage("BUYER_ACCOUNT_NOT_FOUND");
            verifyNoInteractions(exposures);
        }
    }
    @Test void platformScopeCannotUseBuyerProjection() {
        var accounts = mock(CustomerAccountQuery.class); var exposures = mock(CreditExposureQuery.class);
        assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, exposures)
                .readBuyer(context(MembershipRole.COMPANY_OWNER, Surface.PLATFORM), "PEN"))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(accounts, exposures);
    }
    private CurrentAccessContext context(MembershipRole role, Surface surface) {
        return CurrentAccessContext.from(new VerifiedMembership(new Membership(MembershipId.random(), UserId.random(),
                TenantId.random(), WorkspaceId.random(), Set.of(role), MembershipStatus.ACTIVE), TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), surface);
    }
}
