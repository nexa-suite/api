-- Persist the public six-step onboarding workflow in the existing registration aggregate.
-- Older databases did not carry the opaque token column even though the application
-- contract already required it, so the upgrade is deliberately additive.
ALTER TABLE tenant_management.organization_registration
    ADD COLUMN IF NOT EXISTS status_token_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS onboarding_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS last_completed_step SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE tenant_management.organization_registration
    ALTER COLUMN legal_name DROP NOT NULL,
    ALTER COLUMN display_name DROP NOT NULL,
    ALTER COLUMN normalized_legal_name DROP NOT NULL,
    ALTER COLUMN operation_category DROP NOT NULL,
    ALTER COLUMN storage_site_name DROP NOT NULL,
    ALTER COLUMN storage_site_address DROP NOT NULL,
    ALTER COLUMN founder_email DROP NOT NULL,
    ALTER COLUMN founder_display_name DROP NOT NULL,
    ALTER COLUMN workspace_name DROP NOT NULL,
    ALTER COLUMN workspace_slug DROP NOT NULL,
    ALTER COLUMN reference_plan DROP NOT NULL,
    ALTER COLUMN terms_version DROP NOT NULL,
    ALTER COLUMN terms_accepted_at DROP NOT NULL;

CREATE INDEX IF NOT EXISTS ix_organization_registration_resume_token
    ON tenant_management.organization_registration (status_token_hash)
    WHERE status = 'DRAFT' AND status_token_hash IS NOT NULL;

CREATE TABLE tenant_management.organization_registration_draft_idempotency (
    registration_id UUID NOT NULL REFERENCES tenant_management.organization_registration(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    version_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (registration_id, idempotency_key),
    CONSTRAINT ck_registration_draft_idempotency_operation CHECK (operation IN ('CREATE', 'STEP', 'SUBMIT'))
);

CREATE INDEX ix_registration_draft_idempotency_created
    ON tenant_management.organization_registration_draft_idempotency (created_at);
