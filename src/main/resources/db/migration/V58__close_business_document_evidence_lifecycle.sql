-- Business-document evidence is created before bytes arrive. Storage metadata
-- is scoped so an object key cannot become a cross-tenant authorization path.
ALTER TABLE business_documents.evidence_object
    ALTER COLUMN object_key DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS requested_by_membership_id UUID,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS scan_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_scan_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp;

UPDATE business_documents.evidence_object
SET next_scan_at = coalesce(next_scan_at, current_timestamp),
    updated_at = coalesce(updated_at, created_at);

ALTER TABLE business_documents.object_storage_object
    ADD COLUMN IF NOT EXISTS tenant_id UUID,
    ADD COLUMN IF NOT EXISTS workspace_id UUID;

UPDATE business_documents.object_storage_object object_row
SET tenant_id = document_row.tenant_id,
    workspace_id = document_row.workspace_id
FROM business_documents.business_document document_row
WHERE object_row.object_key = document_row.storage_object_key
  AND object_row.tenant_id IS NULL;

UPDATE business_documents.object_storage_object object_row
SET tenant_id = evidence_row.tenant_id,
    workspace_id = evidence_row.workspace_id
FROM business_documents.evidence_object evidence_row
WHERE object_row.object_key = evidence_row.object_key
  AND object_row.tenant_id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_evidence_requested_by') THEN
        ALTER TABLE business_documents.evidence_object
            ADD CONSTRAINT fk_evidence_requested_by
            FOREIGN KEY (workspace_id, requested_by_membership_id)
            REFERENCES tenant_management.workspace_membership (workspace_id, id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_object_storage_scope') THEN
        ALTER TABLE business_documents.object_storage_object
            ADD CONSTRAINT fk_object_storage_scope
            FOREIGN KEY (tenant_id, workspace_id)
            REFERENCES tenant_management.workspace (tenant_id, id);
    END IF;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_evidence_request_idempotency
    ON business_documents.evidence_object (tenant_id, workspace_id, requested_by_membership_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_evidence_scan_queue
    ON business_documents.evidence_object (lifecycle_status, next_scan_at, created_at, id);
CREATE INDEX IF NOT EXISTS ix_object_storage_scope
    ON business_documents.object_storage_object (tenant_id, workspace_id, object_key);

ALTER TABLE business_documents.object_storage_object ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS business_documents_object_storage_object_tenant_workspace_scope
    ON business_documents.object_storage_object;
CREATE POLICY business_documents_object_storage_object_tenant_workspace_scope
    ON business_documents.object_storage_object
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
