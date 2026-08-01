package com.nexa.api.invoicing.application.port;

import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectSnapshot;

/** Internal read contract for future document subjects. It never exposes document storage. */
public interface DocumentSubjectLookupPort {
    DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, DocumentSubjectReference subject);
}
