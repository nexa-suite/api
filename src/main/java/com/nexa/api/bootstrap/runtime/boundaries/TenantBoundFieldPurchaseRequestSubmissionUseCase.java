package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.model.FieldPurchaseRequestModels.Command;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;

/** Runs the complete field submission against one verified Tenant JDBC callback. */
public final class TenantBoundFieldPurchaseRequestSubmissionUseCase
        implements FieldPurchaseRequestSubmissionUseCase {
    private final TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundFieldPurchaseRequestSubmissionUseCase(
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.expiryPolicies = Objects.requireNonNull(expiryPolicies);
        this.compositions = Objects.requireNonNull(compositions);
    }

    @Override
    public PurchaseRequestView submit(CurrentAccessContext context, Command command, String key) {
        return expiryPolicies.inTransaction(context,
                jdbc -> compositions.bindTo(jdbc).fieldPurchaseRequestSubmissions().submit(context, command, key));
    }
}
