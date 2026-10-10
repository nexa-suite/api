package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.bootstrap.runtime.events.TenantOutboxEventConsumer;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded keyset scheduler for BC-11's Tenant source-fact projection. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantBusinessTraceabilityWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantBusinessTraceabilityWorkerScheduler.class);
    private static final int PAGE_SIZE = 50;
    private static final int EVENT_BATCH_SIZE = 50;
    private static final String TRACEABILITY_CONSUMER = "business-traceability-v1";
    private final TenantBusinessDocumentWorkerScopeQuery scopes;
    private final TenantBusinessTraceabilityWorkerRouter router;
    private final TenantBusinessTraceabilityBindingsFactory bindings;
    private final List<TenantOutboxEventConsumer> consumers;
    private final AtomicReference<Scope> after = new AtomicReference<>();
    private final Map<ConsumerScope, EventCursor> eventCursors = new HashMap<>();

    public TenantBusinessTraceabilityWorkerScheduler(TenantBusinessDocumentWorkerScopeQuery scopes,
            TenantBusinessTraceabilityWorkerRouter router,
            TenantBusinessTraceabilityBindingsFactory bindings,
            List<TenantOutboxEventConsumer> consumers) {
        this.scopes = Objects.requireNonNull(scopes, "Central READY Tenant scope query is required");
        this.router = Objects.requireNonNull(router, "Dedicated Tenant traceability worker router is required");
        this.bindings = Objects.requireNonNull(bindings, "Tenant traceability bindings are required");
        this.consumers = List.copyOf(Objects.requireNonNull(consumers, "Tenant outbox owner consumers are required"));
        Set<String> names = this.consumers.stream().map(TenantOutboxEventConsumer::consumerName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (names.size() != this.consumers.size()) {
            throw new IllegalArgumentException("Tenant outbox consumer names must be unique");
        }
    }

    @Scheduled(fixedDelayString = "${nexa.business-traceability.worker-delay-ms:3000}")
    public synchronized void processNextWorkspacePage() {
        Scope cursor = after.get();
        List<Scope> page = List.copyOf(scopes.listReadyWorkspaces(
                cursor == null ? null : cursor.tenantId(), cursor == null ? null : cursor.workspaceId(), PAGE_SIZE));
        if (page.isEmpty()) {
            after.set(null);
            return;
        }
        if (page.size() > PAGE_SIZE) {
            throw new IllegalStateException("Tenant traceability scope query exceeded its bounded page size");
        }
        for (Scope scope : page) process(scope);
        after.set(page.size() < PAGE_SIZE ? null : page.getLast());
    }

    private void process(Scope scope) {
        consumeBusinessFacts(scope);
        for (TenantOutboxEventConsumer consumer : consumers) {
            try {
                consumePending(scope, consumer);
            } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                     | DataAccessException | CannotCreateTransactionException unavailable) {
                LOGGER.warn("One Tenant outbox consumer route was unavailable; consumer={} will retry on a later scan",
                        safeConsumerName(consumer));
            } catch (RuntimeException retryable) {
                LOGGER.warn("One Tenant outbox consumer scan failed; consumer={} will retry on a later scan",
                        safeConsumerName(consumer));
            }
        }
    }

    private void consumeBusinessFacts(Scope scope) {
        ConsumerScope key = new ConsumerScope(scope.tenantId(), scope.workspaceId(), TRACEABILITY_CONSUMER);
        EventCursor cursor = eventCursors.get(key);
        try {
            List<com.nexa.api.shared.events.TenantOutboxEvent> events = router.inTransaction(scope.tenantId(),
                    scope.workspaceId(), (tenantJdbc, ignoredTransactionManager) ->
                            bindings.bindWorker(tenantJdbc).worker().findPendingBusinessFacts(scope.tenantId(),
                                    scope.workspaceId(), EVENT_BATCH_SIZE,
                                    cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.eventId()));
            if (events.isEmpty()) {
                eventCursors.remove(key);
                return;
            }
            for (com.nexa.api.shared.events.TenantOutboxEvent event : events) {
                try {
                    router.inTransaction(scope.tenantId(), scope.workspaceId(), (tenantJdbc, ignoredTransactionManager) ->
                            bindings.bindWorker(tenantJdbc).worker().projectBusinessFact(event));
                } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                         | DataAccessException | CannotCreateTransactionException retryable) {
                    LOGGER.warn("One Tenant business-fact event remains pending after a projection retry; eventId={} eventType={}",
                            event.eventId(), event.eventType());
                } catch (RuntimeException retryable) {
                    LOGGER.warn("One Tenant business-fact event remains pending after a projection retry; eventId={} eventType={}",
                            event.eventId(), event.eventType());
                }
            }
            setCursor(key, events);
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            LOGGER.warn("One Tenant traceability worker scope was unavailable; it will retry on a later scan");
        } catch (RuntimeException retryable) {
            LOGGER.warn("One Tenant business-fact projection failed; it will retry on a later scan");
        }
    }

    private void consumePending(Scope scope, TenantOutboxEventConsumer consumer) {
        String consumerName = requireConsumerName(consumer.consumerName());
        ConsumerScope key = new ConsumerScope(scope.tenantId(), scope.workspaceId(), consumerName);
        EventCursor cursor = eventCursors.get(key);
        Set<String> eventTypes = Set.copyOf(consumer.eventTypes());
        if (eventTypes.isEmpty()) {
            eventCursors.remove(key);
            return;
        }
        List<com.nexa.api.shared.events.TenantOutboxEvent> candidates = router.inTransaction(
                scope.tenantId(), scope.workspaceId(), (tenantJdbc, ignoredTransactionManager) ->
                        bindings.bindWorker(tenantJdbc).worker().findPendingForConsumer(scope.tenantId(),
                                scope.workspaceId(), consumerName, eventTypes, EVENT_BATCH_SIZE,
                                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.eventId()));
        if (candidates.isEmpty()) {
            eventCursors.remove(key);
            return;
        }
        for (com.nexa.api.shared.events.TenantOutboxEvent event : candidates) {
            try {
                var snapshot = router.inTransaction(scope.tenantId(), scope.workspaceId(),
                        (tenantJdbc, ignoredTransactionManager) -> bindings.bindWorker(tenantJdbc).worker()
                                .inspectPendingEvent(event, consumerName, consumer::readTenantSnapshot));
                if (snapshot.isEmpty()) continue;

                // Authority reads happen with no Tenant connection held. The prepared
                // callback carries immutable central facts into a fresh routed tx.
                TenantOutboxEventConsumer.PreparedCallback prepared = Objects.requireNonNull(
                        snapshot.get().preflight(), "Tenant outbox consumer preflight returned no callback");
                router.inTransaction(scope.tenantId(), scope.workspaceId(), (tenantJdbc, transactionManager) ->
                        bindings.bindWorker(tenantJdbc).worker().consumePendingEvent(event, consumerName,
                                transactionManager, (jdbc, tx, candidate) -> prepared.consume(jdbc, tx, candidate)));
            } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                     | DataAccessException | CannotCreateTransactionException retryable) {
                LOGGER.warn("One Tenant outbox event remains pending after an owner callback retry; consumer={} eventId={} eventType={}",
                        consumerName, event.eventId(), event.eventType());
            } catch (RuntimeException retryable) {
                LOGGER.warn("One Tenant outbox event remains pending after an owner callback retry; consumer={} eventId={} eventType={}",
                        consumerName, event.eventId(), event.eventType());
            }
        }
        setCursor(key, candidates);
    }

    private void setCursor(ConsumerScope key, List<com.nexa.api.shared.events.TenantOutboxEvent> events) {
        if (events.size() < EVENT_BATCH_SIZE) {
            eventCursors.remove(key);
            return;
        }
        com.nexa.api.shared.events.TenantOutboxEvent last = events.getLast();
        eventCursors.put(key, new EventCursor(last.createdAt(), last.eventId()));
    }

    private static String requireConsumerName(String name) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Tenant outbox consumer name is invalid");
        }
        return name.strip();
    }

    private static String safeConsumerName(TenantOutboxEventConsumer consumer) {
        try { return requireConsumerName(consumer.consumerName()); }
        catch (RuntimeException invalid) { return "invalid"; }
    }

    private record ConsumerScope(UUID tenantId, UUID workspaceId, String consumerName) { }

    private record EventCursor(Instant createdAt, UUID eventId) { }
}
