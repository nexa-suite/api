package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.businesstraceability.application.model.AuditModels.AuditEventView;
import com.nexa.api.businesstraceability.application.model.AuditModels.AuditPage;
import com.nexa.api.businesstraceability.application.port.in.AuditViewerUseCase;
import com.nexa.api.businesstraceability.application.service.AuditViewerService;
import com.nexa.api.businesstraceability.tenantdatabase.TenantAuditViewerQueryFactory;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Objects;

/** Routes BC-11 business-history reads to the same Tenant store as business facts. */
public final class TenantBoundBusinessTraceabilityUseCase implements AuditViewerUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantAuditViewerQueryFactory queries;

    public TenantBoundBusinessTraceabilityUseCase(TenantBusinessDatabaseRouter router,
            TenantAuditViewerQueryFactory queries) {
        this.router = Objects.requireNonNull(router, "Tenant database router is required");
        this.queries = Objects.requireNonNull(queries, "Tenant audit query factory is required");
    }

    @Override
    public AuditPage list(CurrentAccessContext context, int limit) {
        return execute(context, viewer -> viewer.list(context, limit));
    }

    @Override
    public AuditEventView detail(CurrentAccessContext context, String id) {
        return execute(context, viewer -> viewer.detail(context, id));
    }

    private <T> T execute(CurrentAccessContext context,
            java.util.function.Function<AuditViewerUseCase, T> work) {
        Objects.requireNonNull(context, "Verified Tenant access context is required");
        try {
            return router.inTransaction(context,
                    jdbc -> work.apply(new AuditViewerService(queries.bindTo(jdbc))));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                    "Tenant business-traceability capability is unavailable", unavailable);
        }
    }
}
