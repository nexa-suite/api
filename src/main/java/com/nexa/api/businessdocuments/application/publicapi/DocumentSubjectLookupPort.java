package com.nexa.api.businessdocuments.application.publicapi;

import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectSnapshot;

/** Internal read contract for future document subjects. It never exposes document storage. */
public interface DocumentSubjectLookupPort {
    DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, String actorMembershipId,
                                   DocumentSubjectReference subject);
}
