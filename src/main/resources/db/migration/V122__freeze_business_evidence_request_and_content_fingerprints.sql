ALTER TABLE business_documents.evidence_object
    ADD COLUMN IF NOT EXISTS request_fingerprint CHAR(64),
    ADD COLUMN IF NOT EXISTS upload_content_sha256 CHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_evidence_request_fingerprint') THEN
        ALTER TABLE business_documents.evidence_object
            ADD CONSTRAINT ck_evidence_request_fingerprint
            CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_evidence_upload_content_sha256') THEN
        ALTER TABLE business_documents.evidence_object
            ADD CONSTRAINT ck_evidence_upload_content_sha256
            CHECK (upload_content_sha256 IS NULL OR upload_content_sha256 ~ '^[0-9a-f]{64}$');
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION business_documents.guard_evidence_idempotency_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(NEW.tenant_id, NEW.workspace_id, NEW.client_account_id, NEW.subject_type, NEW.subject_id,
           NEW.requested_by_membership_id, NEW.idempotency_key, NEW.original_filename, NEW.declared_content_type)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.workspace_id, OLD.client_account_id, OLD.subject_type, OLD.subject_id,
           OLD.requested_by_membership_id, OLD.idempotency_key, OLD.original_filename, OLD.declared_content_type)
       OR (OLD.request_fingerprint IS NOT NULL AND NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint)
       OR (OLD.upload_content_sha256 IS NOT NULL AND NEW.upload_content_sha256 IS DISTINCT FROM OLD.upload_content_sha256) THEN
        RAISE EXCEPTION 'Business evidence idempotency identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS business_evidence_idempotency_identity_immutable ON business_documents.evidence_object;
CREATE TRIGGER business_evidence_idempotency_identity_immutable
    BEFORE UPDATE ON business_documents.evidence_object
    FOR EACH ROW EXECUTE FUNCTION business_documents.guard_evidence_idempotency_identity();
