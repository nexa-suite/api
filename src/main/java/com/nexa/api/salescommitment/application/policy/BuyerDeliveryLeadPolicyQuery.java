package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

public interface BuyerDeliveryLeadPolicyQuery {
    BuyerDeliveryLeadPolicyView get(CurrentAccessContext context);
}
