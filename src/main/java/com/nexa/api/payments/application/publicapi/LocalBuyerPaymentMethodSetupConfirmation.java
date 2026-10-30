package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Local deterministic-provider seam; unavailable outside a local simulation provider. */
public interface LocalBuyerPaymentMethodSetupConfirmation {
    PaymentModels.WebhookReceipt confirmLocalBuyerPaymentMethodSetup(CurrentAccessContext context,
            String providerSetupIntentId);
}
