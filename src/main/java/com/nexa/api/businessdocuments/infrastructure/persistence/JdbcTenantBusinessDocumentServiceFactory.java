package com.nexa.api.businessdocuments.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.publicapi.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.DocumentSubjectLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.application.port.ContentScannerPort;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort;
import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentServiceFactory;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds BC-09 persistence and storage rules to one router-owned Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantBusinessDocumentServiceFactory implements TenantBusinessDocumentServiceFactory {
    private final ObjectStoragePort storage;
    private final ContentScannerPort scanner;
    private final DocumentRendererPort renderer;

    public JdbcTenantBusinessDocumentServiceFactory(ObjectStoragePort storage, ContentScannerPort scanner,
                                                     DocumentRendererPort renderer) {
        this.storage = Objects.requireNonNull(storage, "Private object storage is required");
        this.scanner = Objects.requireNonNull(scanner, "Evidence scanner is required");
        this.renderer = Objects.requireNonNull(renderer, "Business document renderer is required");
    }

    @Override
    public Bindings bindTo(JdbcTemplate tenantJdbc, DocumentSubjectLookupPort tenantSubjects,
                           DocumentProjectionLookupPort tenantProjections,
                           CustomerAccountQuery tenantCustomerAccounts,
                           CanonicalOutboxPort tenantCanonicalOutbox) {
        BusinessDocumentService service = new BusinessDocumentService(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"), storage, scanner, renderer,
                Objects.requireNonNull(tenantSubjects, "Tenant document subject query is required"),
                Objects.requireNonNull(tenantProjections, "Tenant document projection query is required"),
                Objects.requireNonNull(tenantCustomerAccounts, "Tenant customer account query is required"),
                null, null, Objects.requireNonNull(tenantCanonicalOutbox, "Tenant canonical outbox is required"));
        return new Bindings(service, service, service);
    }

    @Override
    public BusinessDocumentCommands bindCommandsTo(JdbcTemplate tenantJdbc,
                                                    CanonicalOutboxPort tenantCanonicalOutbox) {
        return new JdbcBusinessDocumentCommands(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantCanonicalOutbox, "Tenant canonical outbox is required"));
    }

    @Override
    public TenantBusinessDocumentWorkerPort bindWorkerTo(
            JdbcTemplate tenantJdbc, DocumentProjectionLookupPort tenantProjections,
            CustomerAccountQuery tenantCustomerAccounts, CanonicalOutboxPort tenantCanonicalOutbox) {
        DocumentSubjectLookupPort noHumanSubjectLookup = (tenantId, workspaceId, actorMembershipId, subject) -> {
            throw new IllegalStateException("Tenant document worker cannot use a human subject lookup");
        };
        return new BusinessDocumentService(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"), storage, scanner, renderer,
                noHumanSubjectLookup,
                Objects.requireNonNull(tenantProjections, "Tenant document projection query is required"),
                Objects.requireNonNull(tenantCustomerAccounts, "Tenant customer account query is required"),
                null, null, Objects.requireNonNull(tenantCanonicalOutbox, "Tenant canonical outbox is required"));
    }
}
