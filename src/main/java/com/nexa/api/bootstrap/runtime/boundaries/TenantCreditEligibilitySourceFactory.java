package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;

import java.util.Objects;

/** Composes BC-07 eligibility from BC-02 account locking and BC-03 customer terms. */
public final class TenantCreditEligibilitySourceFactory {
    private TenantCreditEligibilitySourceFactory() { }

    public static CreditEligibilitySource bindTo(CustomerAccountQuery tenantAccounts) {
        CustomerAccountQuery accounts = Objects.requireNonNull(tenantAccounts,
                "Tenant Customer Account query is required");
        CustomerTermsQuery terms = accounts instanceof CustomerTermsQuery query ? query : null;
        return new CreditEligibilitySource() {
            @Override
            public boolean isCreditAllowed(String tenantId, String workspaceId, String customerAccountId) {
                return allowsCredit(tenantId, workspaceId, customerAccountId);
            }

            @Override
            public boolean lockAndCheckCreditAllowed(String tenantId, String workspaceId, String customerAccountId) {
                if (accounts.lockReference(tenantId, workspaceId, customerAccountId).isEmpty()) return false;
                return allowsCredit(tenantId, workspaceId, customerAccountId);
            }

            private boolean allowsCredit(String tenantId, String workspaceId, String customerAccountId) {
                if (terms == null) return false;
                return terms.findTerms(tenantId, workspaceId, customerAccountId)
                        .map(CustomerTermsQuery.CustomerTermsSnapshot::creditAllowed).orElse(false);
            }
        };
    }
}
