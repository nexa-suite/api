package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.PaymentWebhookInboxWorker;
import com.nexa.api.payments.application.publicapi.TenantPaymentProviderEventProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims only central inbox rows that carry an opaque Tenant payment route. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class TenantPaymentWebhookInboxWorker implements PaymentWebhookInboxWorker {
    private static final int MAX_ATTEMPTS = 10;
    private final JdbcTemplate centralJdbc;
    private final TenantPaymentProviderEventProcessor processor;

    public TenantPaymentWebhookInboxWorker(JdbcTemplate centralJdbc, TenantPaymentProviderEventProcessor processor) {
        this.centralJdbc = Objects.requireNonNull(centralJdbc);
        this.processor = Objects.requireNonNull(processor);
    }

    @Override
    @Scheduled(fixedDelayString = "${nexa.payments.webhook-worker-delay-ms:1000}")
    public void processPending() {
        centralJdbc.update("update payments.stripe_event_inbox set status=case when attempt_count >= ? "
                        + "then 'DEAD_LETTER' else 'FAILED' end,failure_detail='Stale Tenant payment callback attempt',"
                        + "next_attempt_at=current_timestamp,processing_started_at=null,lease_until=null,claim_token=null "
                        + "where payment_route_id is not null and status='PROCESSING' and lease_until <= current_timestamp",
                MAX_ATTEMPTS);
        List<String> eventIds = centralJdbc.query("select event_id from payments.stripe_event_inbox "
                        + "where payment_route_id is not null and status in ('RECEIVED','FAILED') "
                        + "and attempt_count < ? and next_attempt_at <= current_timestamp "
                        + "order by received_at,event_id limit 50",
                (rs, row) -> rs.getString(1), MAX_ATTEMPTS);
        eventIds.forEach(this::processEvent);
    }

    @Override
    public void processEvent(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 160) {
            throw new IllegalArgumentException("Stripe event id is invalid");
        }
        UUID claimToken = UUID.randomUUID();
        int claimed = centralJdbc.update("update payments.stripe_event_inbox set status='PROCESSING',"
                        + "attempt_count=attempt_count+1,failure_detail=null,processing_started_at=current_timestamp,"
                        + "lease_until=current_timestamp + interval '10 minutes',claim_token=? "
                        + "where event_id=? and payment_route_id is not null and status in ('RECEIVED','FAILED') "
                        + "and attempt_count < ? and next_attempt_at <= current_timestamp",
                claimToken, eventId, MAX_ATTEMPTS);
        if (claimed != 1) return;
        try {
            requireClaim(eventId, claimToken);
            VerifiedInboxEvent row = centralJdbc.query("select event_id,event_type,payment_intent_id,payment_status,"
                            + "amount_minor,currency,payment_route_id from payments.stripe_event_inbox "
                            + "where event_id=? and payment_route_id is not null",
                    (rs, n) -> new VerifiedInboxEvent(rs.getString("event_id"), rs.getString("event_type"),
                            rs.getString("payment_intent_id"), rs.getString("payment_status"),
                            rs.getObject("amount_minor", Long.class), rs.getString("currency"),
                            rs.getObject("payment_route_id", UUID.class)), eventId)
                    .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                            "Claimed Tenant payment callback row disappeared"));
            if (row.paymentIntentId() == null || row.routeId() == null) {
                throw new IllegalStateException("Claimed Tenant payment callback has no opaque route");
            }
            requireClaim(eventId, claimToken);
            TenantPaymentProviderEventProcessor.Outcome outcome = processor.process(
                    new TenantPaymentProviderEventProcessor.VerifiedEvent(row.eventId(), row.eventType(),
                            row.paymentIntentId(), row.paymentStatus(), row.amountMinor(), row.currency(), row.routeId()));
            requireClaim(eventId, claimToken);
            String status = outcome == TenantPaymentProviderEventProcessor.Outcome.PROCESSED
                    ? "PROCESSED" : "IGNORED";
            int finalized = centralJdbc.update("update payments.stripe_event_inbox set status=?,"
                            + "processed_at=current_timestamp,next_attempt_at=current_timestamp,"
                            + "processing_started_at=null,lease_until=null,claim_token=null "
                            + "where event_id=? and status='PROCESSING' and claim_token=? "
                            + "and lease_until > current_timestamp",
                    status, eventId, claimToken);
            if (finalized != 1) throw new IllegalStateException("Tenant payment callback inbox claim was lost");
        } catch (RuntimeException failure) {
            int retry = centralJdbc.update("update payments.stripe_event_inbox set status=case when attempt_count >= ? "
                            + "then 'DEAD_LETTER' else 'FAILED' end,failure_detail='Tenant callback processing failed',"
                            + "next_attempt_at=case when attempt_count >= ? then current_timestamp "
                            + "else current_timestamp + (least(power(2,attempt_count),300) * interval '1 second') end,"
                            + "processing_started_at=null,lease_until=null,claim_token=null "
                            + "where event_id=? and status='PROCESSING' and claim_token=?",
                    MAX_ATTEMPTS, MAX_ATTEMPTS, eventId, claimToken);
            if (retry == 0) throw failure;
        }
    }

    private void requireClaim(String eventId, UUID claimToken) {
        Boolean current = centralJdbc.queryForObject("select exists(select 1 from payments.stripe_event_inbox "
                        + "where event_id=? and payment_route_id is not null and status='PROCESSING' "
                        + "and claim_token=? and lease_until > current_timestamp)", Boolean.class, eventId, claimToken);
        if (!Boolean.TRUE.equals(current)) throw new IllegalStateException("Tenant payment callback inbox claim was lost");
    }

    private record VerifiedInboxEvent(String eventId, String eventType, String paymentIntentId,
                                      String paymentStatus, Long amountMinor, String currency, UUID routeId) { }
}
