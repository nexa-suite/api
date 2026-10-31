-- Keep onboarding registrations and membership audit facts attached to the
-- exact Tenant/Workspace scope already represented by their foreign keys.
-- The restricted migrator owns these two tables after the V169 preparation.
-- FORCE RLS hides historical rows from that owner, so suspend FORCE only
-- inside this transactional migration while the preflight and constraints run.
ALTER TABLE tenant_management.organization_registration NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.membership_admin_event NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    scope_row record;
    previous_tenant_id text := coalesce(current_setting('app.current_tenant_id', true), '');
    previous_workspace_id text := coalesce(current_setting('app.current_workspace_id', true), '');
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tenant_management.organization_registration registration
        WHERE (registration.tenant_id IS NULL) <> (registration.workspace_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'V171 cannot enforce organization registration Tenant/Workspace scope: unpaired or mismatched scope exists';
    END IF;

    FOR scope_row IN
        SELECT DISTINCT tenant_id, workspace_id
        FROM tenant_management.organization_registration
        WHERE tenant_id IS NOT NULL
    LOOP
        PERFORM set_config('app.current_tenant_id', scope_row.tenant_id::text, true);
        PERFORM set_config('app.current_workspace_id', scope_row.workspace_id::text, true);
        IF NOT EXISTS (
            SELECT 1
            FROM tenant_management.workspace workspace
            WHERE workspace.tenant_id = scope_row.tenant_id
              AND workspace.id = scope_row.workspace_id
        ) THEN
            RAISE EXCEPTION 'V171 cannot enforce organization registration Tenant/Workspace scope: unpaired or mismatched scope exists';
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM tenant_management.membership_admin_event event
        WHERE NOT EXISTS (
                SELECT 1
                FROM tenant_management.workspace_membership membership
                WHERE membership.workspace_id = event.workspace_id
                  AND membership.id = event.target_membership_id
           )
           OR NOT EXISTS (
                SELECT 1
                FROM tenant_management.workspace_membership membership
                WHERE membership.workspace_id = event.workspace_id
                  AND membership.id = event.actor_membership_id
           )
    ) THEN
        RAISE EXCEPTION 'V171 cannot enforce membership administration event Tenant/Workspace scope: mismatched scope or membership exists';
    END IF;

    FOR scope_row IN
        SELECT DISTINCT tenant_id, workspace_id
        FROM tenant_management.membership_admin_event
    LOOP
        PERFORM set_config('app.current_tenant_id', scope_row.tenant_id::text, true);
        PERFORM set_config('app.current_workspace_id', scope_row.workspace_id::text, true);
        IF NOT EXISTS (
            SELECT 1
            FROM tenant_management.workspace workspace
            WHERE workspace.tenant_id = scope_row.tenant_id
              AND workspace.id = scope_row.workspace_id
        ) THEN
            RAISE EXCEPTION 'V171 cannot enforce membership administration event Tenant/Workspace scope: mismatched scope or membership exists';
        END IF;
    END LOOP;

    PERFORM set_config('app.current_tenant_id', previous_tenant_id, true);
    PERFORM set_config('app.current_workspace_id', previous_workspace_id, true);
END;
$$;

ALTER TABLE tenant_management.organization_registration
    DROP CONSTRAINT fk_registration_tenant,
    DROP CONSTRAINT fk_registration_workspace,
    ADD CONSTRAINT ck_organization_registration_tenant_workspace_pair
        CHECK ((tenant_id IS NULL AND workspace_id IS NULL)
            OR (tenant_id IS NOT NULL AND workspace_id IS NOT NULL)),
    ADD CONSTRAINT fk_organization_registration_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id);

ALTER TABLE tenant_management.membership_admin_event
    DROP CONSTRAINT fk_membership_event_tenant,
    DROP CONSTRAINT fk_membership_event_workspace,
    DROP CONSTRAINT fk_membership_event_target,
    DROP CONSTRAINT fk_membership_event_actor,
    ADD CONSTRAINT fk_membership_event_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    ADD CONSTRAINT fk_membership_event_target
        FOREIGN KEY (workspace_id, target_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    ADD CONSTRAINT fk_membership_event_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE tenant_management.organization_registration FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.membership_admin_event FORCE ROW LEVEL SECURITY;
