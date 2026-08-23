-- Preserve the authoritative activation result so retries can return the same
-- outcome without recreating tenant, workspace, membership or notification data.
ALTER TABLE tenant_management.organization_registration
    ADD COLUMN activated_founder_user_id UUID;

UPDATE tenant_management.organization_registration registration
SET activated_founder_user_id = (
        SELECT membership.user_id
        FROM tenant_management.workspace_membership membership
        JOIN tenant_management.membership_role_definition assignment
          ON assignment.membership_id = membership.id
        JOIN tenant_management.role_definition role_definition
          ON role_definition.id = assignment.role_id
        WHERE membership.workspace_id = registration.workspace_id
          AND role_definition.code = 'company_owner'
        ORDER BY membership.created_at
        LIMIT 1
    )
WHERE registration.status = 'ACTIVE'
  AND registration.activated_founder_user_id IS NULL;

ALTER TABLE tenant_management.organization_registration
    ADD CONSTRAINT fk_registration_activated_founder
        FOREIGN KEY (activated_founder_user_id) REFERENCES iam.user_account(id),
    ADD CONSTRAINT ck_registration_active_outcome
        CHECK (status <> 'ACTIVE' OR (
            tenant_id IS NOT NULL
            AND workspace_id IS NOT NULL
            AND activated_founder_user_id IS NOT NULL
        ));
