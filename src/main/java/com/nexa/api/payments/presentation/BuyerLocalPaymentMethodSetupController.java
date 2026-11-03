package com.nexa.api.payments.presentation;

import com.nexa.api.payments.application.publicapi.LocalBuyerPaymentMethodSetupConfirmation;
import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Hidden local-only route that asks the deterministic provider to emit its signed synthetic card callback. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.payments", name = "provider", havingValue = "deterministic", matchIfMissing = true)
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
@Hidden
@RequestMapping("/api/v1/buyer/payment-methods/setup-intents")
public final class BuyerLocalPaymentMethodSetupController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final LocalBuyerPaymentMethodSetupConfirmation confirmation;

    public BuyerLocalPaymentMethodSetupController(LocalBuyerPaymentMethodSetupConfirmation confirmation) {
        this.confirmation = confirmation;
    }

    @PostMapping("/{setupIntentId}/local-confirmation")
    public ResponseEntity<PaymentModels.WebhookReceipt> confirm(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable String setupIntentId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(confirmation.confirmLocalBuyerPaymentMethodSetup(context, setupIntentId));
    }
}
