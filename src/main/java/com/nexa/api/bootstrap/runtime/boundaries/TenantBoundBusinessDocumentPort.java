package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentModels;
import com.nexa.api.businessdocuments.application.port.BusinessDocumentPort;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Routes every BC-09 HTTP operation through one verified Tenant transaction. */
@Component
@Primary
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-documents.enabled", havingValue = "true")
public final class TenantBoundBusinessDocumentPort implements BusinessDocumentPort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDocumentBindingsFactory bindings;
    private final WarehouseObjectAccess warehouseAccess;

    public TenantBoundBusinessDocumentPort(TenantBusinessDatabaseRouter router,
            TenantBusinessDocumentBindingsFactory bindings, WarehouseObjectAccess warehouseAccess) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.bindings = Objects.requireNonNull(bindings, "Tenant business-document bindings are required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Warehouse access snapshot source is required");
    }

    @Override
    public BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext context, String subjectType,
            UUID subjectId, String documentType, String format, String idempotencyKey) {
        return execute(context, docs -> docs.request(context, subjectType, subjectId, documentType, format, idempotencyKey));
    }

    @Override
    public BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext context,
            int page, int size, String documentType, String status) {
        return execute(context, docs -> docs.list(context, page, size, documentType, status));
    }

    @Override
    public BusinessDocumentModels.DocumentView get(CurrentAccessContext context, UUID documentId) {
        return execute(context, docs -> docs.get(context, documentId));
    }

    @Override
    public List<BusinessDocumentModels.DocumentEventView> events(CurrentAccessContext context, UUID documentId) {
        return execute(context, docs -> docs.events(context, documentId));
    }

    @Override
    public BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext context, UUID documentId,
            String idempotencyKey) {
        return execute(context, docs -> docs.regenerate(context, documentId, idempotencyKey));
    }

    @Override
    public BusinessDocumentModels.GenerationRequestView replace(CurrentAccessContext context, UUID documentId,
            String idempotencyKey) {
        return execute(context, docs -> docs.replace(context, documentId, idempotencyKey));
    }

    @Override
    public BusinessDocumentModels.Download download(CurrentAccessContext context, UUID documentId) {
        return execute(context, docs -> docs.download(context, documentId));
    }

    @Override
    public BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext context, String subjectType,
            UUID subjectId, String originalFilename, String declaredContentType, byte[] content) {
        return execute(context, docs -> docs.uploadEvidence(context, subjectType, subjectId, originalFilename,
                declaredContentType, content));
    }

    @Override
    public BusinessDocumentModels.EvidenceView requestEvidence(CurrentAccessContext context, String subjectType,
            UUID subjectId, String originalFilename, String declaredContentType, String idempotencyKey) {
        return execute(context, docs -> docs.requestEvidence(context, subjectType, subjectId, originalFilename,
                declaredContentType, idempotencyKey));
    }

    @Override
    public BusinessDocumentModels.EvidenceView completeEvidence(CurrentAccessContext context, UUID evidenceId,
            String originalFilename, String declaredContentType, InputStream content, long contentLength,
            String idempotencyKey) {
        return execute(context, docs -> docs.completeEvidence(context, evidenceId, originalFilename,
                declaredContentType, content, contentLength, idempotencyKey));
    }

    @Override
    public BusinessDocumentModels.EvidenceView evidence(CurrentAccessContext context, UUID evidenceId) {
        return execute(context, docs -> docs.evidence(context, evidenceId));
    }

    @Override
    public BusinessDocumentModels.Page<BusinessDocumentModels.EvidenceView> listEvidence(CurrentAccessContext context,
            String subjectType, UUID subjectId, int page, int size) {
        return execute(context, docs -> docs.listEvidence(context, subjectType, subjectId, page, size));
    }

    @Override
    public BusinessDocumentModels.Download downloadEvidence(CurrentAccessContext context, UUID evidenceId) {
        return execute(context, docs -> docs.downloadEvidence(context, evidenceId));
    }

    @Override
    public void deleteEvidence(CurrentAccessContext context, UUID evidenceId) {
        execute(context, docs -> {
            docs.deleteEvidence(context, evidenceId);
            return Boolean.TRUE;
        });
    }

    /** The legacy unscoped scheduler is deliberately not a valid Tenant operation. */
    @Override
    public void processPendingGenerationRequests() {
        throw new IllegalStateException("Tenant document generation requires an explicit verified worker scope");
    }

    private <T> T execute(CurrentAccessContext context, Function<BusinessDocumentPort, T> action) {
        Objects.requireNonNull(context, "Verified Tenant access context is required");
        Set<UUID> activeWarehouseIds = Set.copyOf(warehouseAccess.activeWarehouseIds(context));
        try {
            return router.inTransaction(context, tenantJdbc -> invoke(tenantJdbc, context, activeWarehouseIds, action));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                    "Tenant business-document capability is unavailable", unavailable);
        }
    }

    private <T> T invoke(JdbcTemplate tenantJdbc, CurrentAccessContext context, Set<UUID> activeWarehouseIds,
            Function<BusinessDocumentPort, T> action) {
        var serviceBindings = bindings.bindRequest(tenantJdbc, context, activeWarehouseIds);
        // Download streams are backed by private storage; no JDBC object is returned past this boundary.
        return action.apply(serviceBindings.documents());
    }
}
