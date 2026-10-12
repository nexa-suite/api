package com.nexa.api.salescommitment.application.purchaserequest.port;

import com.nexa.api.salescommitment.application.model.FieldPurchaseRequestModels.Command;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

@NamedInterface("sales-public")
public interface FieldPurchaseRequestSubmissionUseCase {
    PurchaseRequestView submit(CurrentAccessContext context, Command command, String key);
}
