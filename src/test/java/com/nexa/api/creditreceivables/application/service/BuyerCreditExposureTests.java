package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
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
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        when(accounts.findBuyerReference(tenant, workspace, context.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference("current-account", "ACTIVE")));
        when(exposures.find(tenant, workspace, "current-account", "PEN"))
                .thenReturn(new CreditExposureQuery.CreditExposureSnapshot("PEN", new BigDecimal("1000.05"),
                        new BigDecimal("20.01"), new BigDecimal("50.02"), new BigDecimal("30.01"), true));
        when(eligibility.isCreditAllowed(tenant, workspace, "current-account")).thenReturn(true);
        var result = new CreditExposureApplicationService(accounts, eligibility, exposures).readBuyer(context, "pen");
        assertThat(result.clientAccountId()).isEqualTo("current-account");
        assertThat(result.availableCredit()).isEqualByComparingTo("900.01");
        assertThat(result.active()).isTrue();
        assertThat(result.creditEligible()).isTrue();
        verify(accounts).findBuyerReference(tenant, workspace, context.membershipId().toString());
        verifyNoMoreInteractions(accounts);
    }

    @Test void buyerCreditProjectionHidesAvailableBalanceWhenTermsDoNotAllowCredit() {
        var context = context(MembershipRole.BUYER, Surface.PORTAL);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        var accounts = mock(CustomerAccountQuery.class);
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);
        when(accounts.findBuyerReference(tenant, workspace, context.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference("current-account", "ACTIVE")));
        when(eligibility.isCreditAllowed(tenant, workspace, "current-account")).thenReturn(false);
        when(exposures.find(tenant, workspace, "current-account", "PEN"))
                .thenReturn(new CreditExposureQuery.CreditExposureSnapshot("PEN", new BigDecimal("1000.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true));

        var result = new CreditExposureApplicationService(accounts, eligibility, exposures).readBuyer(context, "PEN");

        assertThat(result.active()).isTrue();
        assertThat(result.creditEligible()).isFalse();
        assertThat(result.availableCredit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test void salesOwnerProjectionPreservesExposureAmountsWhenCustomerTermsAreCash() {
        var context = context(MembershipRole.SALES, Surface.PLATFORM);
        var accounts = mock(CustomerAccountQuery.class);
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        when(accounts.findSalesOwnerReference(tenant, workspace, "account", context.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference("account", "ACTIVE")));
        when(eligibility.isCreditAllowed(tenant, workspace, "account")).thenReturn(false);
        when(exposures.find(tenant, workspace, "account", "PEN"))
                .thenReturn(new CreditExposureQuery.CreditExposureSnapshot("PEN", new BigDecimal("1000.00"),
                        new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO, true));

        var result = new CreditExposureApplicationService(accounts, eligibility, exposures).read(context, "account", "PEN");

        assertThat(result.creditEligible()).isFalse();
        assertThat(result.availableCredit()).isEqualByComparingTo("800.00");
        verify(accounts).findSalesOwnerReference(tenant, workspace, "account", context.membershipId().toString());
        verify(accounts, never()).findReference(anyString(), anyString(), anyString());
    }

    @Test void salesOwnerCannotReadOtherSalesExposureAndReceivesAccountNotFound() {
        var context = context(MembershipRole.SALES, Surface.PLATFORM);
        var accounts = mock(CustomerAccountQuery.class);
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        when(accounts.findSalesOwnerReference(tenant, workspace, "other-account", context.membershipId().toString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, eligibility, exposures)
                .read(context, "other-account", "PEN"))
                .isInstanceOf(CreditReceivableOperationException.class)
                .hasMessage("CLIENT_ACCOUNT_NOT_FOUND");

        verify(accounts).findSalesOwnerReference(tenant, workspace, "other-account", context.membershipId().toString());
        verify(accounts, never()).findReference(anyString(), anyString(), anyString());
        verifyNoInteractions(eligibility, exposures);
    }

    @Test void companyOwnerRetainsFullWorkforceReaderScope() {
        assertFullWorkforceReader(MembershipRole.COMPANY_OWNER);
    }

    @Test void bomRetainsFullAuditScopeWithoutClientReadPermission() {
        assertFullWorkforceReader(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
    }

    @Test void tenantAdminAloneCannotReadBuyerCreditExposure() {
        var context = context(MembershipRole.TENANT_ADMIN, Surface.PLATFORM);
        var accounts = mock(CustomerAccountQuery.class);
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);

        assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, eligibility, exposures)
                .read(context, "account", "PEN"))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(accounts, eligibility, exposures);
    }
    @Test void missingOrInactiveRelationshipCannotReadExposure() {
        var context = context(MembershipRole.BUYER, Surface.PORTAL);
        for (var relation : java.util.List.of(Optional.<CustomerAccountReference>empty(), Optional.of(new CustomerAccountReference("account", "SUSPENDED")))) {
            var accounts = mock(CustomerAccountQuery.class); var eligibility = mock(CreditEligibilitySource.class); var exposures = mock(CreditExposureQuery.class);
            when(accounts.findBuyerReference(anyString(), anyString(), anyString())).thenReturn(relation);
            assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, eligibility, exposures).readBuyer(context, "PEN"))
                    .hasMessage("BUYER_ACCOUNT_NOT_FOUND");
            verifyNoInteractions(exposures);
        }
    }
    @Test void platformScopeCannotUseBuyerProjection() {
        var accounts = mock(CustomerAccountQuery.class); var eligibility = mock(CreditEligibilitySource.class); var exposures = mock(CreditExposureQuery.class);
        assertThatThrownBy(() -> new CreditExposureApplicationService(accounts, eligibility, exposures)
                .readBuyer(context(MembershipRole.COMPANY_OWNER, Surface.PLATFORM), "PEN"))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(accounts, eligibility, exposures);
    }
    private CurrentAccessContext context(MembershipRole role, Surface surface) {
        return CurrentAccessContext.from(new VerifiedMembership(new Membership(MembershipId.random(), UserId.random(),
                TenantId.random(), WorkspaceId.random(), Set.of(role), MembershipStatus.ACTIVE), TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), surface);
    }

    private void assertFullWorkforceReader(MembershipRole role) {
        var context = context(role, Surface.PLATFORM);
        var accounts = mock(CustomerAccountQuery.class);
        var eligibility = mock(CreditEligibilitySource.class);
        var exposures = mock(CreditExposureQuery.class);
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString();
        when(accounts.findReference(tenant, workspace, "account"))
                .thenReturn(Optional.of(new CustomerAccountReference("account", "ACTIVE")));
        when(exposures.find(tenant, workspace, "account", "PEN"))
                .thenReturn(new CreditExposureQuery.CreditExposureSnapshot("PEN", BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true));

        new CreditExposureApplicationService(accounts, eligibility, exposures).read(context, "account", "PEN");

        verify(accounts).findReference(tenant, workspace, "account");
        verify(accounts, never()).findSalesOwnerReference(anyString(), anyString(), anyString(), anyString());
    }
}
