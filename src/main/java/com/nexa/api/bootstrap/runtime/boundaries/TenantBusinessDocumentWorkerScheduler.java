package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentOrganizationSnapshotQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery.Scope;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerPort;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
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

/** Bounded keyset scheduler for one evidence scan and one generation job per ready workspace. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-documents.enabled", havingValue = "true")
public final class TenantBusinessDocumentWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantBusinessDocumentWorkerScheduler.class);
    private static final int PAGE_SIZE = 50;

    private final TenantBusinessDocumentWorkerScopeQuery scopes;
    private final TenantBusinessDocumentWorkerRouter router;
    private final TenantBusinessDocumentOrganizationSnapshotQuery organizationSnapshots;
    private final TenantBusinessDocumentBindingsFactory bindings;
    private final AtomicReference<Scope> after = new AtomicReference<>();

    public TenantBusinessDocumentWorkerScheduler(TenantBusinessDocumentWorkerScopeQuery scopes,
            TenantBusinessDocumentWorkerRouter router,
            TenantBusinessDocumentOrganizationSnapshotQuery organizationSnapshots,
            TenantBusinessDocumentBindingsFactory bindings) {
        this.scopes = Objects.requireNonNull(scopes, "Central document worker scope query is required");
        this.router = Objects.requireNonNull(router, "Dedicated Tenant document worker router is required");
        this.organizationSnapshots = Objects.requireNonNull(organizationSnapshots,
                "Central organization snapshot query is required");
        this.bindings = Objects.requireNonNull(bindings, "Tenant document bindings are required");
    }

    @Scheduled(fixedDelayString = "${nexa.documents.worker-delay-ms:3000}")
    public synchronized void processNextWorkspacePage() {
        Scope cursor = after.get();
        List<Scope> page = List.copyOf(scopes.listReadyWorkspaces(
                cursor == null ? null : cursor.tenantId(), cursor == null ? null : cursor.workspaceId(), PAGE_SIZE));
        if (page.isEmpty()) {
            after.set(null);
            return;
        }
        if (page.size() > PAGE_SIZE) {
            throw new IllegalStateException("Tenant document worker scope query exceeded its bounded page size");
        }
        for (Scope scope : page) process(scope);
        after.set(page.size() < PAGE_SIZE ? null : page.getLast());
    }

    private void process(Scope scope) {
        try {
            router.inTransaction(scope.tenantId(), scope.workspaceId(), tenantJdbc -> {
                TenantBusinessDocumentWorkerPort worker = bindings.bindEvidenceWorker(tenantJdbc).worker();
                worker.processPendingEvidenceScans(scope.tenantId(), scope.workspaceId());
                return Boolean.TRUE;
            });
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            unavailable();
        }

        try {
            var snapshot = organizationSnapshots.find(scope.tenantId(), scope.workspaceId());
            if (snapshot.isEmpty()) return;
            router.inTransaction(scope.tenantId(), scope.workspaceId(), tenantJdbc -> {
                var worker = bindings.bindWorker(tenantJdbc, scope.tenantId(), scope.workspaceId(), snapshot.get()).worker();
                worker.processPendingGenerationRequests(scope.tenantId(), scope.workspaceId());
                return Boolean.TRUE;
            });
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            unavailable();
        }
    }

    private static void unavailable() {
        LOGGER.warn("One Tenant business-document worker scope was unavailable; it will be retried on a later scan");
    }

}
