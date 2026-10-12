package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.TenantPaymentWebhookInboxIntake;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTenantPaymentWebhookInboxIntakeTests {
    @Test
    void storesVerifiedTenantRouteInCentralInboxWithEventIdDeduplication() {
        JdbcTemplate centralJdbc = mock(JdbcTemplate.class);
        when(centralJdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        var intake = new JdbcTenantPaymentWebhookInboxIntake(centralJdbc);
        var event = new TenantPaymentWebhookInboxIntake.VerifiedEvent("evt_123", "payment_intent.succeeded",
                "pi_123", "succeeded", 2500L, "PEN", "a".repeat(64), Instant.parse("2026-10-10T12:00:00Z"),
                UUID.fromString("c78d55c4-775a-4d28-a085-08df6c842d10"));

        assertThat(intake.acceptVerifiedEvent(event).status()).isEqualTo("ACCEPTED");
        assertThat(intake.acceptVerifiedEvent(event).status()).isEqualTo("DUPLICATE");

        verify(centralJdbc, times(2)).update(contains("on conflict (event_id) do nothing"), any(Object[].class));
    }
}
