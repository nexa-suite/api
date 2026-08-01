ALTER TABLE tenant_management.workspace_membership
    RENAME COLUMN role TO membership_type;

ALTER TABLE tenant_management.workspace_membership
    ADD CONSTRAINT ck_membership_type CHECK (membership_type IN ('INTERNAL', 'BUYER', 'COMPANY_OWNER', 'SALES', 'WAREHOUSE', 'LOGISTICS'));

CREATE TABLE tenant_management.membership_role_assignment (
    membership_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (membership_id, role),
    CONSTRAINT fk_membership_role_membership
        FOREIGN KEY (workspace_id, membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_membership_role_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_internal_membership_role
        CHECK (role IN ('TENANT_ADMIN', 'COMPANY_OWNER', 'SALES', 'WAREHOUSE', 'LOGISTICS'))
);

CREATE INDEX ix_membership_role_scope
    ON tenant_management.membership_role_assignment (tenant_id, workspace_id, membership_id, role);
CREATE INDEX ix_membership_role_lookup
    ON tenant_management.membership_role_assignment (membership_id, role);

INSERT INTO tenant_management.membership_role_assignment (membership_id, tenant_id, workspace_id, role, assigned_at)
SELECT m.id, w.tenant_id, m.workspace_id, roles.role, current_timestamp
FROM tenant_management.workspace_membership m
JOIN tenant_management.workspace w ON w.id = m.workspace_id
CROSS JOIN LATERAL unnest(
    CASE m.membership_type
        WHEN 'INTERNAL' THEN ARRAY['COMPANY_OWNER']::VARCHAR[]
        WHEN 'COMPANY_OWNER' THEN ARRAY['TENANT_ADMIN', 'COMPANY_OWNER']::VARCHAR[]
        WHEN 'SALES' THEN ARRAY['SALES']::VARCHAR[]
        WHEN 'WAREHOUSE' THEN ARRAY['WAREHOUSE']::VARCHAR[]
        WHEN 'LOGISTICS' THEN ARRAY['LOGISTICS']::VARCHAR[]
        ELSE ARRAY[]::VARCHAR[]
    END
) AS roles(role);

UPDATE tenant_management.workspace_membership
SET membership_type = CASE membership_type
    WHEN 'COMPANY_OWNER' THEN 'INTERNAL'
    WHEN 'SALES' THEN 'INTERNAL'
    WHEN 'WAREHOUSE' THEN 'INTERNAL'
    WHEN 'LOGISTICS' THEN 'INTERNAL'
    ELSE membership_type
END;

ALTER TABLE tenant_management.workspace_membership
    DROP CONSTRAINT ck_membership_type;
ALTER TABLE tenant_management.workspace_membership
    ADD CONSTRAINT ck_membership_type CHECK (membership_type IN ('INTERNAL', 'BUYER'));

COMMENT ON TABLE tenant_management.membership_role_assignment IS
    'Canonical fixed-role authority for internal memberships. Buyer membership remains external and has no assignment rows.';
