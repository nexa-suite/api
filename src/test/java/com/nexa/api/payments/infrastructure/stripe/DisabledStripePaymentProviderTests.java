package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledStripePaymentProviderTests {
    @Test
    void disabledProviderFailsAsAnExplicitTechnicalCapability() {
        StripePaymentProvider provider = new DisabledStripePaymentProvider();

        assertThatThrownBy(() -> provider.createPaymentIntent(
                new StripePaymentProvider.PaymentIntentRequest(100, "PEN", "disabled-test", Map.of())))
                .isInstanceOf(TechnicalFailureException.class)
                .extracting(exception -> ((TechnicalFailureException) exception).kind())
                .isEqualTo(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE);
    }
}
