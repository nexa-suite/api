package com.nexa.api.businessdocuments.application.port;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectReference;

/** Read-only ACL from business contexts into immutable document projections. */
public interface DocumentProjectionLookupPort {
    DocumentProjection lookup(String tenantId, String workspaceId, DocumentSubjectReference subject, BusinessDocumentType documentType);
}
