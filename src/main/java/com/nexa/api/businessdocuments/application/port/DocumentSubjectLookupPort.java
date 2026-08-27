package com.nexa.api.businessdocuments.application.port;

import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectSnapshot;

/** Internal read contract for future document subjects. It never exposes document storage. */
public interface DocumentSubjectLookupPort {
    DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, DocumentSubjectReference subject);
}
