package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery.Scope;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryReservationExpiryWorkerFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryReservationExpiryWorkerRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Routes due BC-05 reservations to each exact READY Tenant business database. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.warehouse-operations.enabled", havingValue = "true")
public final class TenantWarehouseReservationExpiryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantWarehouseReservationExpiryScheduler.class);
    private static final int PAGE_SIZE = 50;

    private final TenantBusinessDocumentWorkerScopeQuery scopes;
    private final TenantInventoryReservationExpiryWorkerRouter router;
    private final TenantInventoryReservationExpiryWorkerFactory expiryWorkers;
    private final TenantChangeEventPersistenceFactory changeEvents;
    private final AtomicReference<Scope> after = new AtomicReference<>();

    public TenantWarehouseReservationExpiryScheduler(TenantBusinessDocumentWorkerScopeQuery scopes,
            TenantInventoryReservationExpiryWorkerRouter router,
            TenantInventoryReservationExpiryWorkerFactory expiryWorkers,
            TenantChangeEventPersistenceFactory changeEvents) {
        this.scopes = Objects.requireNonNull(scopes, "Central READY Tenant scope query is required");
        this.router = Objects.requireNonNull(router, "Dedicated Tenant inventory worker router is required");
        this.expiryWorkers = Objects.requireNonNull(expiryWorkers, "BC-05 Tenant expiry worker factory is required");
        this.changeEvents = Objects.requireNonNull(changeEvents, "Tenant change-event factory is required");
    }

    @Scheduled(fixedDelayString = "${nexa.tenant-business.warehouse-operations.reservation-expiry-worker-delay-ms:60000}")
    public synchronized void processNextWorkspacePage() {
        Scope cursor = after.get();
        List<Scope> page = List.copyOf(scopes.listReadyWorkspaces(
                cursor == null ? null : cursor.tenantId(), cursor == null ? null : cursor.workspaceId(), PAGE_SIZE));
        if (page.isEmpty()) {
            after.set(null);
            return;
        }
        if (page.size() > PAGE_SIZE) {
            throw new IllegalStateException("Tenant reservation expiry scope query exceeded its bounded page size");
        }
        for (Scope scope : page) expire(scope);
        after.set(page.size() < PAGE_SIZE ? null : page.getLast());
    }

    private void expire(Scope scope) {
        try {
            router.inTransaction(scope.tenantId(), scope.workspaceId(), (tenantJdbc, transactionManager) -> {
                var tenantChangeFeed = changeEvents.bindTo(tenantJdbc);
                expiryWorkers.bindTo(tenantJdbc, tenantChangeFeed, transactionManager)
                        .expireReservationsForScope(scope.tenantId(), scope.workspaceId());
                return null;
            });
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException
                 | CannotCreateTransactionException retryable) {
            LOGGER.warn("Tenant reservation expiry will retry after its routed database recovers tenantId={} workspaceId={}",
                    scope.tenantId(), scope.workspaceId());
        } catch (RuntimeException retryable) {
            LOGGER.warn("Tenant reservation expiry will retry after its routed operation recovers tenantId={} workspaceId={}",
                    scope.tenantId(), scope.workspaceId(), retryable);
        }
    }
}
