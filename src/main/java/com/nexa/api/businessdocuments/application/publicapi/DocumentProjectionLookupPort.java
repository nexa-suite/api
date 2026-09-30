package com.nexa.api.businessdocuments.application.publicapi;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.domain.publicapi.BusinessDocumentType;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;

/** Read-only ACL from business contexts into immutable document projections. */
public interface DocumentProjectionLookupPort {
    DocumentProjection lookup(String tenantId, String workspaceId, DocumentSubjectReference subject, BusinessDocumentType documentType);
}
