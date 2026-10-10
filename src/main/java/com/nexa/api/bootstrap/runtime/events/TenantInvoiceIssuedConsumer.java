package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentServiceFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Replays the existing invoice-to-receivable command through the Tenant Payment composition. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantInvoiceIssuedConsumer implements TenantOutboxEventConsumer {
    private static final String EVENT_TYPE = "INVOICE_ISSUED";
    private static final String CONSUMER = "payments-invoice-issued-receivable-v1";

    private final TenantPaymentServiceFactory payments;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final ObjectMapper mapper;

    public TenantInvoiceIssuedConsumer(TenantPaymentServiceFactory payments,
            VerifiedSystemWorkflowAccessContextResolver workflowActors, ObjectMapper mapper) {
        this.payments = Objects.requireNonNull(payments, "Tenant Payment service factory is required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Verified workflow actor resolver is required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        if (!EVENT_TYPE.equals(event.eventType())) throw new IllegalArgumentException("Unexpected Tenant Payment event type");
        Map<String, Object> payload = payload(event.payload());
        UUID salesOrderId = uuid(payload.getOrDefault("salesOrderId", event.aggregateId()));
        return () -> {
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            if (!event.tenantId().equals(actor.tenantId().value())
                    || !event.workspaceId().equals(actor.workspaceId().value())) {
                throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
            }
            return (jdbc, transactionManager, current) -> {
                if (!event.eventId().equals(current.eventId())
                        || !event.tenantId().equals(current.tenantId())
                        || !event.workspaceId().equals(current.workspaceId())
                        || !event.payloadSha256().equals(current.payloadSha256())) {
                    throw new IllegalStateException("Tenant Payment event changed after preflight");
                }
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new IllegalStateException("Tenant invoice consumer requires the router-owned Tenant transaction");
                }
                payments.bindTo(jdbc, transactionManager).createReceivable(actor,
                        new PaymentPersistencePort.ReceivableCommand("SALES_ORDER", salesOrderId, null,
                                "outbox-receivable-" + current.eventId()));
            };
        };
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant invoice event payload is invalid", exception); }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) throw new IllegalArgumentException("Invoice event Sales Order id is required");
        return UUID.fromString(String.valueOf(value));
    }
}
