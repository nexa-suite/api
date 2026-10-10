package com.nexa.api.businesstraceability.tenantdatabase;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.shared.events.TenantOutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

/** Binds BC-11's replayable source-fact consumer to one routed Tenant transaction. */
@FunctionalInterface
public interface TenantBusinessTraceabilityWorkerFactory {
    Worker bindTo(JdbcTemplate tenantJdbc, BusinessTraceabilityCommands tenantTraceability);

    interface Worker {
        int projectPendingBusinessFacts(UUID tenantId, UUID workspaceId, int limit);

        default List<TenantOutboxEvent> findPendingBusinessFacts(UUID tenantId, UUID workspaceId, int limit,
                Instant afterCreatedAt, UUID afterEventId) {
            throw new UnsupportedOperationException("Tenant business-fact paging is not supported");
        }

        default boolean projectBusinessFact(TenantOutboxEvent expected) {
            throw new UnsupportedOperationException("Tenant business-fact projection is not supported");
        }

        default List<TenantOutboxEvent> findPendingForConsumer(UUID tenantId, UUID workspaceId,
                String consumerName, Set<String> eventTypes, int limit) {
            throw new UnsupportedOperationException("Tenant outbox owner callbacks are not supported");
        }

        default List<TenantOutboxEvent> findPendingForConsumer(UUID tenantId, UUID workspaceId,
                String consumerName, Set<String> eventTypes, int limit,
                Instant afterCreatedAt, UUID afterEventId) {
            return findPendingForConsumer(tenantId, workspaceId, consumerName, eventTypes, limit);
        }

        default <T> Optional<T> inspectPendingEvent(TenantOutboxEvent expected, String consumerName,
                EventRead<T> read) {
            throw new UnsupportedOperationException("Tenant outbox owner callbacks are not supported");
        }

        default boolean consumePendingEvent(TenantOutboxEvent expected, String consumerName,
                PlatformTransactionManager tenantTransactionManager, ConsumerWork work) {
            throw new UnsupportedOperationException("Tenant outbox owner callbacks are not supported");
        }
    }

    @FunctionalInterface
    interface EventRead<T> {
        T read(JdbcTemplate tenantJdbc, TenantOutboxEvent event);
    }

    @FunctionalInterface
    interface ConsumerWork {
        void consume(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
                     TenantOutboxEvent event);
    }
}
