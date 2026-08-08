package com.nexa.api.payments.presentation;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.service.PaymentServiceFacade;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Local Stripe-compatible browser acceptance seam; never registered in production profiles. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.payments", name = "provider", havingValue = "stripe")
@Hidden
@RequestMapping("/api/v1")
public final class StripeBrowserTestController {
    private static final String ACCESS = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final PaymentServiceFacade service;

    public StripeBrowserTestController(PaymentServiceFacade service) { this.service = service; }

    @PostMapping("/receivables/{receivableId}/payment-intents/test-confirm")
    public PaymentModels.PaymentView confirm(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                             @PathVariable UUID receivableId,
                                             @Valid @RequestBody ConfirmRequest request) {
        return service.confirmTestCardPayment(context, receivableId, request.clientSecret());
    }

    public record ConfirmRequest(@NotBlank String clientSecret) { }
}
