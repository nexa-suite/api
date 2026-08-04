package com.nexa.api.invoicing.application.service;

import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.invoicing.application.port.BusinessDocumentPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Profile("!test")
public class BusinessDocumentServiceFacade {
    private final BusinessDocumentPort port;
    public BusinessDocumentServiceFacade(BusinessDocumentPort port) { this.port = port; }
    public BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext c, String subjectType, UUID subjectId, String documentType, String format, String key) { return port.request(c, subjectType, subjectId, documentType, format, key); }
    public BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext c, int page, int size, String type, String status) { return port.list(c, page, size, type, status); }
    public BusinessDocumentModels.DocumentView get(CurrentAccessContext c, UUID id) { return port.get(c, id); }
    public BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext c, UUID id, String key) { return port.regenerate(c, id, key); }
    public BusinessDocumentModels.Download download(CurrentAccessContext c, UUID id) { return port.download(c, id); }
    public BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext c, String subjectType, UUID subjectId, String filename, String contentType, byte[] content) { return port.uploadEvidence(c, subjectType, subjectId, filename, contentType, content); }
}
