CREATE TABLE tenant_management.membership_admin_event (
    id UUID PRIMARY KEY,
    event_type VARCHAR(48) NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    target_membership_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    before_role VARCHAR(48),
    before_status VARCHAR(32),
    after_role VARCHAR(48),
    after_status VARCHAR(32),
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_membership_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant(id),
    CONSTRAINT fk_membership_event_workspace FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace(id),
    CONSTRAINT fk_membership_event_target FOREIGN KEY (target_membership_id) REFERENCES tenant_management.workspace_membership(id),
    CONSTRAINT fk_membership_event_actor FOREIGN KEY (actor_membership_id) REFERENCES tenant_management.workspace_membership(id)
);
