CREATE TABLE iam.refresh_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    surface VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    family_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_session_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_refresh_session_user
        FOREIGN KEY (user_id) REFERENCES iam.user_account (id),
    CONSTRAINT fk_refresh_session_membership
        FOREIGN KEY (membership_id) REFERENCES tenant_management.workspace_membership (id),
    CONSTRAINT fk_refresh_session_replacement
        FOREIGN KEY (replaced_by_session_id) REFERENCES iam.refresh_session (id)
);
