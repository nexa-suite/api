CREATE INDEX ix_refresh_session_user_id ON iam.refresh_session (user_id);
CREATE INDEX ix_refresh_session_membership_id ON iam.refresh_session (membership_id);
CREATE INDEX ix_refresh_session_family_id ON iam.refresh_session (family_id);
CREATE INDEX ix_refresh_session_expires_at ON iam.refresh_session (expires_at);
CREATE INDEX ix_workspace_tenant_id ON tenant_management.workspace (tenant_id);
CREATE INDEX ix_workspace_membership_user_id ON tenant_management.workspace_membership (user_id);
CREATE INDEX ix_workspace_membership_workspace_id ON tenant_management.workspace_membership (workspace_id);
