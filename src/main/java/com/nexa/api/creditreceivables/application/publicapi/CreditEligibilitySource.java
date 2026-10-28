package com.nexa.api.creditreceivables.application.publicapi;

/**
 * BC-07 consumer-owned source for the authoritative customer-terms credit decision.
 * Runtime composition supplies the decision from BC-03 and uses the BC-02 account
 * query to lock the scoped account row when a credit mutation requires stability.
 */
public interface CreditEligibilitySource {
    boolean isCreditAllowed(String tenantId, String workspaceId, String customerAccountId);

    /** Requires the caller's tenant transaction to remain open through the credit mutation. */
    boolean lockAndCheckCreditAllowed(String tenantId, String workspaceId, String customerAccountId);
}
