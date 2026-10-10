CREATE TABLE iam.internal_support_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'SALES_ORDER',
    resource_id UUID NOT NULL,
    requested_by_operator_id UUID NOT NULL,
    approved_by_operator_id UUID,
    owner_consent_user_id UUID,
    owner_consent_membership_id UUID,
    owner_consented_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_operator_id UUID,
    revoked_by_owner_user_id UUID,
    revoked_by_owner_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_internal_support_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_internal_support_resource
        CHECK (resource_type = 'SALES_ORDER'),
    CONSTRAINT ck_internal_support_status
        CHECK (status IN ('AWAITING_OWNER_CONSENT', 'AWAITING_INDEPENDENT_APPROVAL', 'APPROVED', 'REVOKED')),
    CONSTRAINT ck_internal_support_deadline
        CHECK (expires_at > created_at AND expires_at <= created_at + interval '1 hour'),
    CONSTRAINT ck_internal_support_approval
        CHECK (approved_by_operator_id IS NULL OR approved_by_operator_id <> requested_by_operator_id),
    CONSTRAINT ck_internal_support_owner_consent_facts
        CHECK ((owner_consented_at IS NULL AND owner_consent_user_id IS NULL AND owner_consent_membership_id IS NULL)
            OR (owner_consented_at IS NOT NULL AND owner_consent_user_id IS NOT NULL AND owner_consent_membership_id IS NOT NULL)),
    CONSTRAINT ck_internal_support_approved_state
        CHECK (status <> 'APPROVED' OR (approved_by_operator_id IS NOT NULL AND owner_consented_at IS NOT NULL)),
    CONSTRAINT ck_internal_support_revoke_actor
        CHECK (revoked_by_operator_id IS NULL OR revoked_by_owner_user_id IS NULL),
    CONSTRAINT ck_internal_support_owner_revoke_facts
        CHECK ((revoked_by_owner_user_id IS NULL AND revoked_by_owner_membership_id IS NULL)
            OR (revoked_by_owner_user_id IS NOT NULL AND revoked_by_owner_membership_id IS NOT NULL)),
    CONSTRAINT ck_internal_support_revoked_state
        CHECK ((status = 'REVOKED' AND revoked_at IS NOT NULL
            AND num_nonnulls(revoked_by_operator_id, revoked_by_owner_user_id) = 1)
            OR (status <> 'REVOKED' AND revoked_at IS NULL
                AND revoked_by_operator_id IS NULL AND revoked_by_owner_user_id IS NULL)),
    CONSTRAINT ck_internal_support_version CHECK (version >= 0)
);

ALTER TABLE iam.internal_support_request
    ADD CONSTRAINT uq_internal_support_audit_scope
        UNIQUE (id, tenant_id, workspace_id, resource_type, resource_id, expires_at);

CREATE INDEX ix_internal_support_scope_status
    ON iam.internal_support_request (tenant_id, workspace_id, status, expires_at);
CREATE INDEX ix_internal_support_requester_created
    ON iam.internal_support_request (requested_by_operator_id, created_at DESC);
CREATE INDEX ix_internal_support_approver_created
    ON iam.internal_support_request (approved_by_operator_id, created_at DESC)
    WHERE approved_by_operator_id IS NOT NULL;

CREATE TABLE iam.internal_support_audit_event (
    id UUID PRIMARY KEY,
    support_request_id UUID NOT NULL,
    action VARCHAR(48) NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_id UUID NOT NULL,
    actor_membership_id UUID,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT fk_internal_support_audit_request
        FOREIGN KEY (support_request_id, tenant_id, workspace_id, resource_type, resource_id, expires_at)
        REFERENCES iam.internal_support_request (id, tenant_id, workspace_id, resource_type, resource_id, expires_at),
    CONSTRAINT ck_internal_support_audit_actor
        CHECK (actor_type IN ('INTERNAL_OPERATOR', 'COMPANY_OWNER')),
    CONSTRAINT ck_internal_support_audit_actor_facts
        CHECK ((actor_type = 'COMPANY_OWNER' AND actor_membership_id IS NOT NULL)
            OR (actor_type = 'INTERNAL_OPERATOR' AND actor_membership_id IS NULL)),
    CONSTRAINT ck_internal_support_audit_action
        CHECK (action IN ('REQUESTED', 'OWNER_CONSENTED', 'APPROVED', 'REVOKED', 'ORDER_READ'))
);

CREATE INDEX ix_internal_support_audit_request_time
    ON iam.internal_support_audit_event (support_request_id, occurred_at, id);

CREATE OR REPLACE FUNCTION iam.reject_internal_support_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Internal support audit events are append-only';
END;
$$;

CREATE TRIGGER internal_support_audit_append_only
    BEFORE UPDATE OR DELETE ON iam.internal_support_audit_event
    FOR EACH ROW EXECUTE FUNCTION iam.reject_internal_support_audit_mutation();

CREATE OR REPLACE FUNCTION iam.guard_internal_support_request_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
        OR NEW.resource_type <> OLD.resource_type OR NEW.resource_id <> OLD.resource_id
        OR NEW.requested_by_operator_id <> OLD.requested_by_operator_id OR NEW.expires_at <> OLD.expires_at
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Support scope and deadline are immutable';
    END IF;
    IF OLD.status IN ('REVOKED') OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED','REVOKED'))
        OR (OLD.status = 'AWAITING_OWNER_CONSENT' AND NEW.status NOT IN ('AWAITING_OWNER_CONSENT','AWAITING_INDEPENDENT_APPROVAL','REVOKED'))
        OR (OLD.status = 'AWAITING_INDEPENDENT_APPROVAL' AND NEW.status NOT IN ('AWAITING_INDEPENDENT_APPROVAL','APPROVED','REVOKED')) THEN
        RAISE EXCEPTION 'Invalid internal support request transition';
    END IF;
    IF OLD.owner_consented_at IS NOT NULL AND (
        NEW.owner_consented_at IS DISTINCT FROM OLD.owner_consented_at
        OR NEW.owner_consent_user_id IS DISTINCT FROM OLD.owner_consent_user_id
        OR NEW.owner_consent_membership_id IS DISTINCT FROM OLD.owner_consent_membership_id
    ) THEN
        RAISE EXCEPTION 'Owner consent is immutable';
    END IF;
    IF OLD.approved_by_operator_id IS NOT NULL AND NEW.approved_by_operator_id IS DISTINCT FROM OLD.approved_by_operator_id THEN
        RAISE EXCEPTION 'Independent approval is immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND (
        NEW.owner_consented_at IS NULL OR NEW.approved_by_operator_id IS NULL
        OR NEW.approved_by_operator_id = NEW.requested_by_operator_id
    ) THEN
        RAISE EXCEPTION 'Owner consent and independent approval are required';
    END IF;
    IF NEW.status = 'REVOKED' AND (NEW.revoked_at IS NULL
        OR num_nonnulls(NEW.revoked_by_operator_id, NEW.revoked_by_owner_user_id) <> 1) THEN
        RAISE EXCEPTION 'A revocation actor and timestamp are required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER internal_support_request_update_guard
    BEFORE UPDATE ON iam.internal_support_request
    FOR EACH ROW EXECUTE FUNCTION iam.guard_internal_support_request_update();

REVOKE ALL PRIVILEGES ON iam.internal_support_request, iam.internal_support_audit_event FROM PUBLIC;
REVOKE ALL ON FUNCTION iam.reject_internal_support_audit_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION iam.guard_internal_support_request_update() FROM PUBLIC;

ALTER TABLE iam.internal_support_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.internal_support_request FORCE ROW LEVEL SECURITY;
CREATE POLICY internal_support_request_owner_scope
    ON iam.internal_support_request
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );
CREATE POLICY internal_support_request_verified_operator_scope
    ON iam.internal_support_request
    USING (nullif(current_setting('app.current_internal_operator_id', true), '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
    WITH CHECK (nullif(current_setting('app.current_internal_operator_id', true), '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

-- The internal health query projects only workflow identifiers and statuses.
-- It never selects registration contact fields or onboarding payloads.
CREATE POLICY organization_registration_internal_console_read
    ON tenant_management.organization_registration
    FOR SELECT
    USING (nullif(current_setting('app.current_internal_operator_id', true), '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

ALTER TABLE iam.internal_support_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.internal_support_audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY internal_support_audit_owner_insert_scope
    ON iam.internal_support_audit_event
    FOR INSERT
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );
CREATE POLICY internal_support_audit_verified_operator_scope
    ON iam.internal_support_audit_event
    USING (nullif(current_setting('app.current_internal_operator_id', true), '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
    WITH CHECK (nullif(current_setting('app.current_internal_operator_id', true), '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        REVOKE ALL PRIVILEGES ON iam.internal_support_request, iam.internal_support_audit_event FROM nexa_runtime;
        GRANT SELECT, INSERT ON iam.internal_support_request TO nexa_runtime;
        GRANT UPDATE (approved_by_operator_id, owner_consent_user_id, owner_consent_membership_id,
            owner_consented_at, status, revoked_at, revoked_by_operator_id,
            revoked_by_owner_user_id, revoked_by_owner_membership_id, updated_at, version)
            ON iam.internal_support_request TO nexa_runtime;
        GRANT SELECT, INSERT ON iam.internal_support_audit_event TO nexa_runtime;
    END IF;
END;
$$;
