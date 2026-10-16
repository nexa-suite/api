package com.nexa.api.businessdocuments.application.publicapi;

import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectSnapshot;

/** Internal read contract for future document subjects. It never exposes document storage. */
public interface DocumentSubjectLookupPort {
    DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, String actorMembershipId,
                                   DocumentSubjectReference subject);

    /** Evidence may bind to a draft POD; document generation continues to use the finalized subject lookup. */
    default DocumentSubjectSnapshot lookupForEvidence(String tenantId, String workspaceId, String actorMembershipId,
                                                       String actorUserId, DocumentSubjectReference subject) {
        return lookup(tenantId, workspaceId, actorMembershipId, subject);
    }
}
