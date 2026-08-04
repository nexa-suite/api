CREATE SCHEMA IF NOT EXISTS business_documents;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE IF NOT EXISTS integration.outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    causation_id UUID,
    schema_version VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT fk_outbox_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED','DEAD_LETTER')),
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count BETWEEN 0 AND 20),
    CONSTRAINT ck_outbox_payload_size CHECK (octet_length(payload::text) <= 200000)
);
CREATE INDEX IF NOT EXISTS ix_outbox_delivery ON integration.outbox_event (status, next_attempt_at, created_at, event_id);
CREATE INDEX IF NOT EXISTS ix_outbox_scope_time ON integration.outbox_event (tenant_id, workspace_id, occurred_at, event_id);

CREATE TABLE IF NOT EXISTS integration.inbox_event (
    consumer_name VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    result VARCHAR(32) NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT fk_inbox_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_inbox_result CHECK (result IN ('PROCESSED','IGNORED','FAILED'))
);

CREATE TABLE IF NOT EXISTS business_documents.object_storage_object (
    object_key VARCHAR(500) PRIMARY KEY,
    bucket_name VARCHAR(120) NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL,
    private_object BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_object_key_randomized CHECK (length(object_key) BETWEEN 20 AND 500 AND object_key NOT LIKE '%..%'),
    CONSTRAINT ck_object_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_object_size CHECK (byte_size BETWEEN 0 AND 52428800)
);

CREATE TABLE IF NOT EXISTS business_documents.business_document (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    document_number VARCHAR(120),
    version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    format VARCHAR(8) NOT NULL,
    storage_object_key VARCHAR(500),
    checksum_sha256 CHAR(64),
    content_type VARCHAR(160),
    byte_size BIGINT,
    generated_at TIMESTAMPTZ,
    generated_by_membership_id UUID,
    failure_code VARCHAR(80),
    failure_detail VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_business_document_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_business_document_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_business_document_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_business_document_object FOREIGN KEY (storage_object_key)
        REFERENCES business_documents.object_storage_object (object_key),
    CONSTRAINT ck_business_document_type CHECK (document_type IN ('ORDER_SUMMARY','PURCHASE_REQUEST_SUMMARY','COMMERCIAL_INVOICE_DRAFT','DELIVERY_GUIDE_DRAFT','POD_REPORT','INCIDENT_REPORT','PAYMENT_RECEIPT')),
    CONSTRAINT ck_business_document_status CHECK (status IN ('REQUESTED','GENERATING','GENERATED','FAILED','SUPERSEDED','VOIDED')),
    CONSTRAINT ck_business_document_format CHECK (format IN ('PDF','CSV','XML')),
    CONSTRAINT ck_business_document_version CHECK (version >= 1),
    CONSTRAINT ck_business_document_checksum CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_business_document_size CHECK (byte_size IS NULL OR byte_size BETWEEN 0 AND 52428800)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_business_document_version ON business_documents.business_document
    (tenant_id, workspace_id, subject_type, subject_id, document_type, format, version);
CREATE INDEX IF NOT EXISTS ix_business_document_list ON business_documents.business_document
    (tenant_id, workspace_id, document_type, status, created_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_business_document_subject ON business_documents.business_document
    (tenant_id, workspace_id, subject_type, subject_id, version DESC, id);

CREATE TABLE IF NOT EXISTS business_documents.document_generation_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    requested_by_membership_id UUID NOT NULL,
    document_id UUID,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    format VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_document_generation_idempotency UNIQUE (tenant_id, workspace_id, requested_by_membership_id, idempotency_key),
    CONSTRAINT fk_document_generation_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_document_generation_document FOREIGN KEY (tenant_id, workspace_id, document_id)
        REFERENCES business_documents.business_document (tenant_id, workspace_id, id),
    CONSTRAINT ck_document_generation_status CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_document_generation_format CHECK (format IN ('PDF','CSV','XML')),
    CONSTRAINT ck_document_generation_attempts CHECK (attempt_count BETWEEN 0 AND 10)
);
CREATE INDEX IF NOT EXISTS ix_document_generation_queue ON business_documents.document_generation_request
    (status, requested_at, id);

CREATE TABLE IF NOT EXISTS business_documents.evidence_object (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    declared_content_type VARCHAR(160) NOT NULL,
    detected_content_type VARCHAR(160),
    original_filename VARCHAR(255) NOT NULL,
    checksum_sha256 CHAR(64),
    byte_size BIGINT,
    width INTEGER,
    height INTEGER,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    scanned_at TIMESTAMPTZ,
    CONSTRAINT fk_evidence_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_evidence_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_evidence_object FOREIGN KEY (object_key)
        REFERENCES business_documents.object_storage_object (object_key),
    CONSTRAINT ck_evidence_lifecycle CHECK (lifecycle_status IN ('REQUESTED','UPLOADING','QUARANTINED','SCANNING','AVAILABLE','REJECTED','DELETED')),
    CONSTRAINT ck_evidence_size CHECK (byte_size IS NULL OR byte_size BETWEEN 0 AND 10485760)
);
CREATE INDEX IF NOT EXISTS ix_evidence_subject ON business_documents.evidence_object
    (tenant_id, workspace_id, subject_type, subject_id, lifecycle_status, created_at DESC);
