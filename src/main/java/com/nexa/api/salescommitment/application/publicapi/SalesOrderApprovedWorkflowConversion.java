package com.nexa.api.salescommitment.application.publicapi;

import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow technical command for converting an already-approved Purchase Request. */
public interface SalesOrderApprovedWorkflowConversion {
    SalesOrderView convertApprovedBySystemWorkflow(CurrentAccessContext context, String purchaseRequestId,
            long purchaseRequestVersion, String idempotencyKey, String note);
}
