package com.nexa.api.businessdocuments.application.service;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentModels;
import com.nexa.api.businessdocuments.application.port.BusinessDocumentPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
public class BusinessDocumentServiceFacade {
    private final BusinessDocumentPort port;
    public BusinessDocumentServiceFacade(BusinessDocumentPort port) { this.port = port; }
    public BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext c, String subjectType, UUID subjectId, String documentType, String format, String key) { return port.request(c, subjectType, subjectId, documentType, format, key); }
    public BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext c, int page, int size, String type, String status) { return port.list(c, page, size, type, status); }
    public BusinessDocumentModels.DocumentView get(CurrentAccessContext c, UUID id) { return port.get(c, id); }
    public List<BusinessDocumentModels.DocumentEventView> events(CurrentAccessContext c, UUID id) { return port.events(c, id); }
    public BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext c, UUID id, String key) { return port.regenerate(c, id, key); }
    public BusinessDocumentModels.Download download(CurrentAccessContext c, UUID id) { return port.download(c, id); }
    public BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext c, String subjectType, UUID subjectId, String filename, String contentType, byte[] content) { return port.uploadEvidence(c, subjectType, subjectId, filename, contentType, content); }
    public BusinessDocumentModels.EvidenceView requestEvidence(CurrentAccessContext c, String subjectType, UUID subjectId, String filename, String contentType, String key) { return port.requestEvidence(c, subjectType, subjectId, filename, contentType, key); }
    public BusinessDocumentModels.EvidenceView completeEvidence(CurrentAccessContext c, UUID id, String filename, String contentType, InputStream content, long length, String key) { return port.completeEvidence(c, id, filename, contentType, content, length, key); }
    public BusinessDocumentModels.EvidenceView evidence(CurrentAccessContext c, UUID id) { return port.evidence(c, id); }
    public BusinessDocumentModels.Page<BusinessDocumentModels.EvidenceView> listEvidence(CurrentAccessContext c, String subjectType, UUID subjectId, int page, int size) { return port.listEvidence(c, subjectType, subjectId, page, size); }
    public BusinessDocumentModels.Download downloadEvidence(CurrentAccessContext c, UUID id) { return port.downloadEvidence(c, id); }
    public void deleteEvidence(CurrentAccessContext c, UUID id) { port.deleteEvidence(c, id); }
}
