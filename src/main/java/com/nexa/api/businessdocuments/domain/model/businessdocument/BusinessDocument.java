package com.nexa.api.businessdocuments.domain.model.businessdocument;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable document aggregate. Draft fiscal documents never imply SUNAT acceptance. */
public final class BusinessDocument {
    private final UUID id;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID clientAccountId;
    private final String subjectType;
    private final UUID subjectId;
    private final BusinessDocumentType type;
    private final BusinessDocumentFormat format;
    private final int version;
    private BusinessDocumentStatus status;
    private String objectKey;
    private String checksum;
    private String contentType;
    private long size;
    private Instant generatedAt;
    private String failureCode;
    private String failureDetail;

    private BusinessDocument(UUID id, UUID tenantId, UUID workspaceId, UUID clientAccountId, String subjectType, UUID subjectId,
            BusinessDocumentType type, BusinessDocumentFormat format, int version, BusinessDocumentStatus status) {
        this.id = Objects.requireNonNull(id); this.tenantId = Objects.requireNonNull(tenantId); this.workspaceId = Objects.requireNonNull(workspaceId);
        this.clientAccountId = clientAccountId; this.subjectType = required(subjectType, "Subject type", 64); this.subjectId = Objects.requireNonNull(subjectId);
        this.type = Objects.requireNonNull(type); this.format = Objects.requireNonNull(format);
        if (version < 1) throw new IllegalArgumentException("Document version must be positive");
        this.version = version; this.status = Objects.requireNonNull(status);
    }

    public static BusinessDocument requested(UUID tenantId, UUID workspaceId, UUID clientAccountId, String subjectType, UUID subjectId,
            BusinessDocumentType type, BusinessDocumentFormat format, int version) {
        return new BusinessDocument(UUID.randomUUID(), tenantId, workspaceId, clientAccountId, subjectType, subjectId, type, format, version, BusinessDocumentStatus.REQUESTED);
    }

    public void startGeneration() {
        if (status != BusinessDocumentStatus.REQUESTED && status != BusinessDocumentStatus.FAILED) throw new IllegalStateException("Document is not pending generation");
        status = BusinessDocumentStatus.GENERATING;
    }
    public void generated(String objectKey, String checksum, String contentType, long size, Instant generatedAt) {
        if (status != BusinessDocumentStatus.GENERATING) throw new IllegalStateException("Document is not generating");
        this.objectKey = required(objectKey, "Object key", 500); this.checksum = required(checksum, "Checksum", 64); this.contentType = required(contentType, "Content type", 160);
        if (size < 0 || size > 52428800) throw new IllegalArgumentException("Document size is invalid");
        this.size = size; this.generatedAt = Objects.requireNonNull(generatedAt); this.status = BusinessDocumentStatus.GENERATED; this.failureCode = null; this.failureDetail = null;
    }
    public void failed(String code, String detail) { status = BusinessDocumentStatus.FAILED; failureCode = required(code, "Failure code", 80); failureDetail = detail == null ? null : detail.substring(0, Math.min(2000, detail.length())); }

    private static String required(String value, String label, int max) { String normalized = Objects.requireNonNull(value, label + " is required").trim(); if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid"); return normalized; }
    public UUID id() { return id; } public UUID tenantId() { return tenantId; } public UUID workspaceId() { return workspaceId; } public UUID clientAccountId() { return clientAccountId; }
    public String subjectType() { return subjectType; } public UUID subjectId() { return subjectId; } public BusinessDocumentType type() { return type; } public BusinessDocumentFormat format() { return format; }
    public int version() { return version; } public BusinessDocumentStatus status() { return status; } public String objectKey() { return objectKey; } public String checksum() { return checksum; }
    public String contentType() { return contentType; } public long size() { return size; } public Instant generatedAt() { return generatedAt; } public String failureCode() { return failureCode; } public String failureDetail() { return failureDetail; }
}
