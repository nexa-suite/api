package com.nexa.api.payments.domain;

import com.nexa.api.payments.domain.model.payment.Payment;
import com.nexa.api.payments.domain.model.payment.PaymentAttempt;
import com.nexa.api.payments.domain.model.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentDomainTests {
    @Test
    void providerEventsAreIdempotentAndCannotRegress() {
        Payment payment = Payment.rehydrate("pay-1", BigDecimal.TEN, PaymentStatus.PROCESSING);

        assertThat(payment.applyProviderStatus(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(payment.applyProviderStatus(PaymentStatus.SUCCEEDED)).isFalse();
        assertThatThrownBy(() -> payment.applyProviderStatus(PaymentStatus.PROCESSING))
                .isInstanceOf(IllegalArgumentException.class);

        PaymentAttempt attempt = PaymentAttempt.rehydrate("attempt-1", 1, PaymentStatus.PROCESSING);
        attempt.applyStatus(PaymentStatus.SUCCEEDED);
        assertThat(attempt.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
