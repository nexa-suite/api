package com.nexa.api.invoicing.application.port;

import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

public interface BusinessDocumentPort {
    BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext context, String subjectType, UUID subjectId, String documentType, String format, String idempotencyKey);
    BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext context, int page, int size, String documentType, String status);
    BusinessDocumentModels.DocumentView get(CurrentAccessContext context, UUID documentId);
    BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext context, UUID documentId, String idempotencyKey);
    BusinessDocumentModels.Download download(CurrentAccessContext context, UUID documentId);
    BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, String originalFilename, String declaredContentType, byte[] content);
    void processPendingGenerationRequests();
}
