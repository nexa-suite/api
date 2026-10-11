package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.publicapi.PaymentWebhookInboxWorker;
import com.nexa.api.payments.application.publicapi.TenantPaymentProviderEventProcessor;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePaymentCallbackRouter;
import com.nexa.api.bootstrap.runtime.events.CanonicalOutbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentDocumentIntegrationIT extends PaymentIntegrationSupport {
    @Autowired
    private PaymentWebhookInboxWorker inboxWorker;

    @Autowired
    private TenantPaymentProviderEventProcessor providerEventProcessor;

    @Autowired
    private JdbcPaymentProviderRouteRegistry paymentRoutes;

    @Autowired
    private TenantBusinessDatabasePaymentCallbackRouter callbackRouter;

    @Test
    void tenantProviderProcessorSettlesPaymentAndRequestsReceipt() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String eventId = "evt-document-processor-" + uuid();
        String payload = stripePayload(eventId, "payment_intent.succeeded", fixture.providerPaymentIntentId(),
                "succeeded", fixture.receivable().amount().movePointRight(2).longValueExact(),
                fixture.receivable().currency(), tenantId(), workspaceId());

        mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", stripeSignature(payload)).content(payload))
                .andExpect(status().isAccepted());

        var event = jdbc.queryForObject("select event_id,event_type,payment_intent_id,payment_status,amount_minor,"
                        + "currency,payment_route_id from payments.stripe_event_inbox where event_id=?",
                (rs, row) -> new TenantPaymentProviderEventProcessor.VerifiedEvent(rs.getString("event_id"),
                        rs.getString("event_type"), rs.getString("payment_intent_id"),
                        rs.getString("payment_status"), rs.getObject("amount_minor", Long.class),
                        rs.getString("currency"), rs.getObject("payment_route_id", UUID.class)), eventId);

        assertThat(providerEventProcessor.process(event)).isEqualTo(TenantPaymentProviderEventProcessor.Outcome.PROCESSED);
        assertThat(tenantJdbc().queryForObject("select status from payments.payment where id=?", String.class,
                UUID.fromString(fixture.paymentId()))).isEqualTo("SUCCEEDED");
        assertThat(tenantJdbc().queryForObject("select status from payments.receivable where id=?", String.class,
                fixture.receivable().id())).isEqualTo("PAID");
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.document_generation_request "
                        + "where subject_type='PAYMENT' and subject_id=? and document_type='PAYMENT_RECEIPT'",
                Integer.class, UUID.fromString(fixture.paymentId()))).isEqualTo(1);

        var route = paymentRoutes.findForVerifiedIntent(fixture.providerPaymentIntentId());
        var outboxPrivileges = callbackRouter.inTransaction(route, (workerJdbc, transactionManager) -> {
            Boolean canReadEventId = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'integration.outbox_event', 'event_id', 'SELECT')", Boolean.class);
            Boolean canReadPayload = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'integration.outbox_event', 'payload', 'SELECT')", Boolean.class);
            Boolean canReadTenantId = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'integration.outbox_event', 'tenant_id', 'SELECT')", Boolean.class);
            Boolean canReadWorkspaceId = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'integration.outbox_event', 'workspace_id', 'SELECT')", Boolean.class);
            Boolean canReadEventType = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'integration.outbox_event', 'event_type', 'SELECT')", Boolean.class);
            Boolean canReadAuditId = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'audit.event', 'id', 'SELECT')", Boolean.class);
            Boolean canReadAuditSafeMetadata = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'audit.event', 'safe_metadata', 'SELECT')", Boolean.class);
            Boolean canReadPaymentEventPaymentId = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'payments.payment_event', 'payment_id', 'SELECT')", Boolean.class);
            Boolean canReadPaymentEventEventKey = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'payments.payment_event', 'event_key', 'SELECT')", Boolean.class);
            Boolean canReadPaymentEventMetadata = workerJdbc.queryForObject("select has_column_privilege(current_user, "
                    + "'payments.payment_event', 'event_type', 'SELECT')", Boolean.class);
            UUID outboxEventId = CanonicalOutbox.append(workerJdbc, "PAYMENT_SUCCEEDED", "Payment", route.paymentId(),
                    route.tenantId().value(), route.workspaceId().value(), Instant.now(),
                    "payment-" + route.paymentId(), null, "1.0", Map.of("paymentId", route.paymentId()));
            Integer eventCount = workerJdbc.queryForObject(
                    "select count(*) from integration.outbox_event where event_id=?", Integer.class, outboxEventId);
            return new OutboxPrivileges(canReadEventId, canReadPayload, canReadTenantId,
                    canReadWorkspaceId, canReadEventType, canReadAuditId, canReadAuditSafeMetadata,
                    canReadPaymentEventPaymentId, canReadPaymentEventEventKey,
                    canReadPaymentEventMetadata, eventCount);
        });
        assertThat(outboxPrivileges.canReadEventId()).isTrue();
        assertThat(outboxPrivileges.canReadPayload()).isFalse();
        assertThat(outboxPrivileges.canReadTenantId()).isFalse();
        assertThat(outboxPrivileges.canReadWorkspaceId()).isFalse();
        assertThat(outboxPrivileges.canReadEventType()).isFalse();
        assertThat(outboxPrivileges.canReadAuditId()).isTrue();
        assertThat(outboxPrivileges.canReadAuditSafeMetadata()).isFalse();
        assertThat(outboxPrivileges.canReadPaymentEventPaymentId()).isTrue();
        assertThat(outboxPrivileges.canReadPaymentEventEventKey()).isTrue();
        assertThat(outboxPrivileges.canReadPaymentEventMetadata()).isFalse();
        assertThat(outboxPrivileges.eventCount()).isEqualTo(1);
    }

    @Test
    void successfulPaymentSettlesReceivableAndRequestsPaymentReceipt() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String eventId = "evt-document-" + uuid();
        String payload = stripePayload(eventId, "payment_intent.succeeded", fixture.providerPaymentIntentId(), "succeeded",
                fixture.receivable().amount().movePointRight(2).longValueExact(), fixture.receivable().currency(), tenantId(), workspaceId());

        mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", stripeSignature(payload)).content(payload))
                .andExpect(status().isAccepted());
        inboxWorker.processPending();

        assertThat(tenantJdbc().queryForObject("select status from payments.payment where id=?", String.class, java.util.UUID.fromString(fixture.paymentId()))).isEqualTo("SUCCEEDED");
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?", java.math.BigDecimal.class, fixture.receivable().id()))
                .isEqualByComparingTo(fixture.receivable().amount());
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.document_generation_request where subject_type='PAYMENT' and subject_id=? and document_type='PAYMENT_RECEIPT'", Integer.class, java.util.UUID.fromString(fixture.paymentId()))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.payment_event where payment_id=? and event_key=?", Integer.class, java.util.UUID.fromString(fixture.paymentId()), eventId)).isEqualTo(1);
    }

    private record OutboxPrivileges(Boolean canReadEventId, Boolean canReadPayload, Boolean canReadTenantId,
                                    Boolean canReadWorkspaceId, Boolean canReadEventType, Boolean canReadAuditId,
                                    Boolean canReadAuditSafeMetadata, Boolean canReadPaymentEventPaymentId,
                                    Boolean canReadPaymentEventEventKey, Boolean canReadPaymentEventMetadata,
                                    Integer eventCount) { }
}
