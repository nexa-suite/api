package com.nexa.api.businessdocuments.application.model;

import java.time.Instant;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class BusinessDocumentModels {
    private BusinessDocumentModels() { }
    public record Page<T>(List<T> items, int page, int size, long total) { public Page { items = List.copyOf(items); } }
    public record DocumentView(String id, String clientAccountId, String subjectType, String subjectId, String documentType,
            String documentNumber, int version, String status, String format, String storageObjectKey, String checksumSha256,
            String contentType, long byteSize, Instant generatedAt, String failureCode, String failureDetail,
            Instant createdAt, Instant updatedAt, String replacementOfDocumentId) { }
    public record GenerationRequestView(String id, String documentId, String subjectType, String subjectId, String documentType,
            String format, String status, Instant requestedAt, Instant completedAt) { }
    public record DocumentEventView(String eventId, String eventType, String status, Instant occurredAt,
            Instant processedAt, int attemptCount) { }
    public record EvidenceView(String id, String subjectType, String subjectId, String lifecycleStatus, String declaredContentType,
            String detectedContentType, String originalFilename, String checksumSha256, long byteSize, Instant createdAt, Instant scannedAt,
            String failureCode, Instant updatedAt) { }
    public record Download(String filename, String contentType, Supplier<InputStream> contentSource,
                           long byteSize, String checksumSha256) {
        public Download {
            Objects.requireNonNull(contentSource, "Download content source is required");
        }

        public Download(String filename, String contentType, InputStream content, long byteSize,
                        String checksumSha256) {
            this(filename, contentType, () -> Objects.requireNonNull(content,
                    "Download content is required"), byteSize, checksumSha256);
        }

        public InputStream content() {
            return Objects.requireNonNull(contentSource.get(), "Download content source returned no stream");
        }
    }
}
