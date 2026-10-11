package com.nexa.api.businessdocuments.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentUseCase;
import com.nexa.api.businessdocuments.application.publicapi.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.DocumentSubjectLookupPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates request-local BC-09 adapters over exactly one router-owned Tenant session. */
public interface TenantBusinessDocumentServiceFactory {
    BusinessDocumentUseCase bindTo(JdbcTemplate tenantJdbc, DocumentSubjectLookupPort tenantSubjects,
                    DocumentProjectionLookupPort tenantProjections, CustomerAccountQuery tenantCustomerAccounts,
                    CanonicalOutboxPort tenantCanonicalOutbox);

    /** Binds the payment receipt command without constructing unused document source adapters. */
    BusinessDocumentCommands bindCommandsTo(JdbcTemplate tenantJdbc, CanonicalOutboxPort tenantCanonicalOutbox);

    TenantBusinessDocumentWorkerPort bindWorkerTo(JdbcTemplate tenantJdbc,
                    DocumentProjectionLookupPort tenantProjections, CustomerAccountQuery tenantCustomerAccounts,
                    CanonicalOutboxPort tenantCanonicalOutbox);
}
