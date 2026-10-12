package com.nexa.api.businessdocuments.application.port;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentUseCase;

/** Internal adapter contract retaining the legacy unscoped local scheduler hook. */
public interface BusinessDocumentPort extends BusinessDocumentUseCase {
    void processPendingGenerationRequests();
}
