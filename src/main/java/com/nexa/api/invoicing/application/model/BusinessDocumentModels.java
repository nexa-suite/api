package com.nexa.api.invoicing.application.model;

import java.time.Instant;
import java.io.InputStream;
import java.util.List;

public final class BusinessDocumentModels {
    private BusinessDocumentModels() { }
    public record Page<T>(List<T> items, int page, int size, long total) { public Page { items = List.copyOf(items); } }
    public record DocumentView(String id, String clientAccountId, String subjectType, String subjectId, String documentType,
            String documentNumber, int version, String status, String format, String storageObjectKey, String checksumSha256,
            String contentType, long byteSize, Instant generatedAt, String failureCode, String failureDetail,
            Instant createdAt, Instant updatedAt) { }
    public record GenerationRequestView(String id, String documentId, String subjectType, String subjectId, String documentType,
            String format, String status, Instant requestedAt, Instant completedAt) { }
    public record EvidenceView(String id, String subjectType, String subjectId, String lifecycleStatus, String declaredContentType,
            String detectedContentType, String originalFilename, String checksumSha256, long byteSize, Instant createdAt, Instant scannedAt,
            String failureCode, Instant updatedAt) { }
    public record Download(String filename, String contentType, InputStream content, long byteSize, String checksumSha256) { }
}
