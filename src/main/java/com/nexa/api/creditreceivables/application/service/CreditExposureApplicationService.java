package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.math.BigDecimal;

/** Application boundary for the authorized, current customer credit exposure projection. */
@Service
@Profile("!test")
public class CreditExposureApplicationService implements CreditExposureUseCase {
    private final CustomerAccountQuery customerAccounts;
    private final CreditEligibilitySource creditEligibility;
    private final CreditExposureQuery exposures;

    public CreditExposureApplicationService(CustomerAccountQuery customerAccounts, CreditEligibilitySource creditEligibility,
                                            CreditExposureQuery exposures) {
        this.customerAccounts = customerAccounts;
        this.creditEligibility = creditEligibility;
        this.exposures = exposures;
    }

    @Transactional(readOnly = true)
    @Override
    public CreditExposureUseCase.CreditExposureView read(CurrentAccessContext context, String clientAccountId, String currency) {
        requireWorkforceRead(context);
        String tenantId = context.tenantId().toString();
        String workspaceId = context.workspaceId().toString();
        var lookup = isSalesOwnerScoped(context)
                ? customerAccounts.findSalesOwnerReference(tenantId, workspaceId, clientAccountId,
                        context.membershipId().toString())
                : customerAccounts.findReference(tenantId, workspaceId, clientAccountId);
        var customer = lookup
                .orElseThrow(() -> new CreditReceivableOperationException("CLIENT_ACCOUNT_NOT_FOUND"));
        return projection(tenantId, workspaceId, customer.id(), currency, false);
    }

    @Transactional(readOnly = true)
    @Override
    public CreditExposureUseCase.CreditExposureView readBuyer(CurrentAccessContext context, String currency) {
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(PermissionKey.PAYMENT_READ);
        String tenantId = context.tenantId().toString();
        String workspaceId = context.workspaceId().toString();
        var customer = customerAccounts.findBuyerReference(tenantId, workspaceId, context.membershipId().toString())
                .filter(value -> value.active())
                .orElseThrow(() -> new CreditReceivableOperationException("BUYER_ACCOUNT_NOT_FOUND"));
        return projection(tenantId, workspaceId, customer.id(), currency, true);
    }

    private CreditExposureUseCase.CreditExposureView projection(String tenantId, String workspaceId, String customerId,
                                                                 String currency, boolean buyerProjection) {
        String normalizedCurrency = normalizeCurrency(currency);
        CreditExposureQuery.CreditExposureSnapshot snapshot = exposures.find(
                tenantId, workspaceId, customerId, normalizedCurrency);
        boolean creditEligible = snapshot.active()
                && creditEligibility.isCreditAllowed(tenantId, workspaceId, customerId);
        BigDecimal availableCredit = buyerProjection && !creditEligible
                ? BigDecimal.ZERO : snapshot.availableCredit();
        return new CreditExposureUseCase.CreditExposureView(customerId, snapshot.currency(), snapshot.creditLimit(),
                snapshot.ledgerExposure(), snapshot.outstandingReceivables(), snapshot.reservedExposure(),
                snapshot.used(), availableCredit, snapshot.active(), creditEligible, Instant.now());
    }

    private static void requireWorkforceRead(CurrentAccessContext context) {
        context.requireSurface(Surface.PLATFORM);
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
        }
        if (context.hasRole(MembershipRole.TENANT_ADMIN)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)
                && !context.hasRole(MembershipRole.SALES)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            throw new AccessPolicyViolation("Tenant administration does not grant access to Buyer account data");
        }
        if (!context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            context.requirePermission(PermissionKey.CLIENT_READ);
        }
    }

    private static boolean isSalesOwnerScoped(CurrentAccessContext context) {
        return context.hasRole(MembershipRole.SALES)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
    }

    private static String normalizeCurrency(String currency) {
        String normalized = currency == null || currency.isBlank() ? "PEN" : currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be a three-letter code");
        return normalized;
    }

}
