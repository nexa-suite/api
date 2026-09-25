-- Close direct Tenant/Workspace RLS coverage without changing immutable V1-V100.
-- The three exceptional tables have explicit read/write policies below because
-- invitation acceptance, public registration and system role templates are
-- pre-context application paths with existing bearer/system authorization.

DO $$
DECLARE
    target regclass;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'audit.event'::regclass,
        'catalog_management.brand'::regclass,
        'catalog_management.category'::regclass,
        'catalog_management.command_idempotency'::regclass,
        'catalog_management.product'::regclass,
        'catalog_management.product_asset_reference'::regclass,
        'catalog_management.product_family'::regclass,
        'catalog_management.product_presentation'::regclass,
        'catalog_management.product_price'::regclass,
        'catalog_management.product_variant'::regclass,
        'catalog_management.product_visibility'::regclass,
        'catalog_management.promotion'::regclass,
        'catalog_management.promotion_category'::regclass,
        'catalog_management.promotion_client_account'::regclass,
        'catalog_management.promotion_product'::regclass,
        'catalog_management.promotion_rule'::regclass,
        'catalog_management.promotion_sku'::regclass,
        'catalog_management.seed_import_history'::regclass,
        'catalog_management.sellable_sku'::regclass,
        'catalog_management.sku_price'::regclass,
        'sales.manual_order_idempotency'::regclass,
        'sales.sales_order_sequence'::regclass,
        'tenant_management.custom_field_definition'::regclass,
        'tenant_management.membership_admin_event'::regclass,
        'tenant_management.membership_authorization_state'::regclass,
        'tenant_management.membership_role_assignment'::regclass,
        'tenant_management.membership_role_definition'::regclass,
        'tenant_management.workspace_creation_idempotency'::regclass
    ] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format(
            'CREATE POLICY v102_tenant_workspace_scope ON %s '
            'USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) '
            'AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) '
            'WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) '
            'AND workspace_id::text = current_setting(''app.current_workspace_id'', true))',
            target
        );
    END LOOP;
END;
$$;

-- Tenant-wide rows are directly owned by a Tenant even though they do not
-- carry a workspace_id. Their existing runtime callers are Tenant-scoped
-- configuration, plan, pricing, projection, and invitation-idempotency paths.
DO $$
DECLARE
    target regclass;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'tenant_management.organization_invitation_idempotency'::regclass,
        'tenant_management.organization_settings'::regclass,
        'tenant_management.reference_plan_assignment'::regclass,
        'tenant_management.regional_settings'::regclass,
        'tenant_management.tenant_security_settings'::regclass,
        'tenant_management.unit_preferences'::regclass
    ] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format(
            'CREATE POLICY v102_tenant_scope ON %s '
            'USING (tenant_id::text = current_setting(''app.current_tenant_id'', true)) '
            'WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true))',
            target
        );
    END LOOP;
END;
$$;

-- The Tenant root uses id as its own scope key. Current-context requests and
-- registration activation write under that exact ID. Local bootstrap may
-- discover one existing tenant by its configured slug. Identity-first login
-- and public preview may resolve only a Tenant attached to a visible
-- workspace row; the workspace table separately bounds those pre-context
-- reads to one slug, one membership, or a named cross-scope worker scan.
ALTER TABLE tenant_management.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY v102_tenant_root_scope ON tenant_management.tenant
    USING (id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY v102_tenant_root_bootstrap_read ON tenant_management.tenant
    FOR SELECT
    USING (lower(slug) = current_setting('app.bootstrap_tenant_slug', true));
CREATE POLICY v102_tenant_root_visible_workspace_read ON tenant_management.tenant
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tenant_management.workspace w
        WHERE w.tenant_id = tenant_management.tenant.id
    ));

-- Workspace is a direct Tenant-owned identity row. Existing Tenant
-- administration intentionally lists sibling workspaces within the same
-- Tenant. Pre-context login/preview is limited to one requested slug, refresh
-- revalidation to one supplied membership, identity-first context discovery
-- to memberships of the authenticated user, and startup/worker enumeration to
-- an explicit transaction-local scan setting. All writes still require the
-- current Tenant predicate.
ALTER TABLE tenant_management.workspace ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.workspace FORCE ROW LEVEL SECURITY;
CREATE POLICY v102_workspace_tenant_scope ON tenant_management.workspace
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY v102_workspace_login_or_preview_slug ON tenant_management.workspace
    FOR SELECT
    USING (lower(slug) = current_setting('app.workspace_lookup_slug', true));
CREATE POLICY v102_workspace_membership_revalidation ON tenant_management.workspace
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tenant_management.workspace_membership m
        WHERE m.workspace_id = tenant_management.workspace.id
          AND m.id::text = current_setting('app.workspace_membership_lookup_id', true)
          AND m.user_id::text = current_setting('app.workspace_membership_lookup_user_id', true)
    ));
CREATE POLICY v102_workspace_access_context_user ON tenant_management.workspace
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tenant_management.workspace_membership m
        WHERE m.workspace_id = tenant_management.workspace.id
          AND m.user_id::text = current_setting('app.access_context_user_id', true)
    ));
CREATE POLICY v102_workspace_cross_scope_scan ON tenant_management.workspace
    FOR SELECT
    USING (current_setting('app.cross_scope_workspace_scan', true) = 'true');

-- Invitations are normally visible only in the current Tenant/Workspace. The
-- single pre-context acceptance lookup is additionally bounded by the opaque
-- invitation token hash; the adapter resolves and installs its scope before
-- membership, role, audit, or invitation writes.
ALTER TABLE tenant_management.organization_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.organization_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY v102_tenant_workspace_scope ON tenant_management.organization_invitation
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
CREATE POLICY v102_invitation_token_accept ON tenant_management.organization_invitation
    FOR SELECT
    USING (status = 'PENDING'
       AND btrim(token_hash::text) = current_setting('app.invitation_accept_token_hash', true));

-- Public drafts and submitted registrations remain pre-context records. Their
-- row ID plus existing opaque status-token hash gates public access; activation
-- and rejection use the already-authorized SYSTEM operator path and name one
-- registration row. No predicate exposes all pending registrations.
ALTER TABLE tenant_management.organization_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.organization_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY v102_registration_read ON tenant_management.organization_registration
    FOR SELECT
    USING (
        (tenant_id::text = current_setting('app.current_tenant_id', true)
         AND workspace_id::text = current_setting('app.current_workspace_id', true))
        OR (id::text = current_setting('app.organization_registration_id', true)
            AND btrim(status_token_hash::text) = current_setting('app.organization_registration_token_hash', true))
        OR id::text = current_setting('app.organization_registration_operator_id', true)
    );
CREATE POLICY v102_registration_insert ON tenant_management.organization_registration
    FOR INSERT
    WITH CHECK (
        tenant_id IS NULL AND workspace_id IS NULL
        AND id::text = current_setting('app.organization_registration_id', true)
            AND btrim(status_token_hash::text) = current_setting('app.organization_registration_token_hash', true)
            AND ((status = 'DRAFT' AND current_setting('app.organization_registration_write_mode', true) = 'DRAFT_CREATE')
                 OR (status = 'PENDING_ACTIVATION' AND current_setting('app.organization_registration_write_mode', true) = 'PUBLIC_SUBMIT'))
    );
CREATE POLICY v102_registration_update ON tenant_management.organization_registration
    FOR UPDATE
    USING (
        (tenant_id::text = current_setting('app.current_tenant_id', true)
         AND workspace_id::text = current_setting('app.current_workspace_id', true))
        OR (id::text = current_setting('app.organization_registration_id', true)
            AND btrim(status_token_hash::text) = current_setting('app.organization_registration_token_hash', true))
        OR id::text = current_setting('app.organization_registration_operator_id', true)
    )
    WITH CHECK (
        (tenant_id::text = current_setting('app.current_tenant_id', true)
         AND workspace_id::text = current_setting('app.current_workspace_id', true))
        OR (id::text = current_setting('app.organization_registration_id', true)
            AND btrim(status_token_hash::text) = current_setting('app.organization_registration_token_hash', true)
            AND tenant_id IS NULL AND workspace_id IS NULL
            AND status IN ('DRAFT', 'PENDING_ACTIVATION'))
        OR id::text = current_setting('app.organization_registration_operator_id', true)
    );

-- System role definitions are readable in every scope. Runtime writes remain
-- confined to non-null Tenant/Workspace custom-role rows.
ALTER TABLE tenant_management.role_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.role_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY v102_role_definition_read ON tenant_management.role_definition
    FOR SELECT
    USING (
        (tenant_id IS NULL AND workspace_id IS NULL
         AND role_type IN ('SYSTEM_RESERVED', 'SYSTEM_TEMPLATE'))
        OR (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND (workspace_id IS NULL
                 OR workspace_id::text = current_setting('app.current_workspace_id', true)))
    );
CREATE POLICY v102_role_definition_insert ON tenant_management.role_definition
    FOR INSERT
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND (workspace_id IS NULL OR workspace_id::text = current_setting('app.current_workspace_id', true))
        AND role_type = 'CUSTOM');
CREATE POLICY v102_role_definition_update ON tenant_management.role_definition
    FOR UPDATE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND (workspace_id IS NULL OR workspace_id::text = current_setting('app.current_workspace_id', true))
        AND role_type = 'CUSTOM')
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND (workspace_id IS NULL OR workspace_id::text = current_setting('app.current_workspace_id', true))
        AND role_type = 'CUSTOM');
CREATE POLICY v102_role_definition_delete ON tenant_management.role_definition
    FOR DELETE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND (workspace_id IS NULL OR workspace_id::text = current_setting('app.current_workspace_id', true))
        AND role_type = 'CUSTOM');

COMMENT ON POLICY v102_invitation_token_accept ON tenant_management.organization_invitation IS
    'Pre-context acceptance may read one pending invitation only after the adapter sets the presented opaque token hash; writes require the resolved Tenant/Workspace scope.';
COMMENT ON POLICY v102_registration_read ON tenant_management.organization_registration IS
    'Public status/draft access requires row ID plus opaque token hash; operator access is restricted to the authorized registration ID; scoped rows require matching Tenant/Workspace.';
COMMENT ON POLICY v102_role_definition_read ON tenant_management.role_definition IS
    'Global system role templates are runtime read-only; all custom role rows require the matching Tenant/Workspace scope.';
COMMENT ON POLICY v102_workspace_access_context_user ON tenant_management.workspace IS
    'Identity-first access-context discovery is limited to workspaces with a membership for the trusted transaction-local app.access_context_user_id; role assignments are then read under each candidate Tenant/Workspace scope.';
