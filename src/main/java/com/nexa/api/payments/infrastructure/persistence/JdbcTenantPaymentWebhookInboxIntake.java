package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.model.PaymentModels.WebhookReceipt;
import com.nexa.api.payments.application.publicapi.TenantPaymentWebhookInboxIntake;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Objects;

/** Persists verified Tenant-routed callbacks in the central technical inbox owned by BC-08. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class JdbcTenantPaymentWebhookInboxIntake implements TenantPaymentWebhookInboxIntake {
    private final JdbcTemplate centralJdbc;

    public JdbcTenantPaymentWebhookInboxIntake(JdbcTemplate centralJdbc) {
        this.centralJdbc = Objects.requireNonNull(centralJdbc, "Central payment inbox JDBC access is required");
    }

    @Override
    public WebhookReceipt acceptVerifiedEvent(VerifiedEvent event) {
        Objects.requireNonNull(event, "Verified Stripe event is required");
        int inserted = centralJdbc.update("insert into payments.stripe_event_inbox "
                        + "(event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,"
                        + "signature_sha256,received_at,payment_route_id) values (?,?,?,?,?,?,?,?,?) "
                        + "on conflict (event_id) do nothing",
                event.eventId(), event.eventType(), event.paymentIntentId(), event.paymentStatus(),
                event.amountMinor(), event.currency(), event.signatureSha256(), Timestamp.from(event.receivedAt()),
                event.paymentRouteId());
        return new WebhookReceipt(event.eventId(), inserted == 0 ? "DUPLICATE" : "ACCEPTED");
    }
}
