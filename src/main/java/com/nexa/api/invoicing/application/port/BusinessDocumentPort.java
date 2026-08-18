package com.nexa.api.invoicing.application.port;

import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface BusinessDocumentPort {
    BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext context, String subjectType, UUID subjectId, String documentType, String format, String idempotencyKey);
    BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext context, int page, int size, String documentType, String status);
    BusinessDocumentModels.DocumentView get(CurrentAccessContext context, UUID documentId);
    List<BusinessDocumentModels.DocumentEventView> events(CurrentAccessContext context, UUID documentId);
    BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext context, UUID documentId, String idempotencyKey);
    BusinessDocumentModels.Download download(CurrentAccessContext context, UUID documentId);
    BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, String originalFilename, String declaredContentType, byte[] content);
    BusinessDocumentModels.EvidenceView requestEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, String originalFilename, String declaredContentType, String idempotencyKey);
    BusinessDocumentModels.EvidenceView completeEvidence(CurrentAccessContext context, UUID evidenceId, String originalFilename, String declaredContentType, InputStream content, long contentLength, String idempotencyKey);
    BusinessDocumentModels.EvidenceView evidence(CurrentAccessContext context, UUID evidenceId);
    BusinessDocumentModels.Page<BusinessDocumentModels.EvidenceView> listEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, int page, int size);
    BusinessDocumentModels.Download downloadEvidence(CurrentAccessContext context, UUID evidenceId);
    void deleteEvidence(CurrentAccessContext context, UUID evidenceId);
    void processPendingGenerationRequests();
}
