ALTER TABLE business_documents.document_generation_request
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_document_generation_retry_queue
    ON business_documents.document_generation_request (status, next_attempt_at, requested_at, id);

-- A crashed worker must not leave a request permanently PROCESSING. The worker
-- claims only retryable rows and keeps the original request for observability.
UPDATE business_documents.document_generation_request
SET status='PENDING', processing_started_at=NULL, next_attempt_at=current_timestamp
WHERE status='PROCESSING'
  AND (processing_started_at IS NULL OR processing_started_at < current_timestamp - interval '10 minutes');
