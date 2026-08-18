-- A membership-role assignment changes one membership. A role-permission
-- change changes every membership assigned to that role. Keep those scopes
-- separate so adding a user to a role cannot revoke unrelated live sessions.
CREATE OR REPLACE FUNCTION tenant_management.bump_authorization_membership(membership_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tenant_management.membership_authorization_state
        (membership_id, tenant_id, workspace_id, authorization_version, updated_at)
    SELECT m.id, w.tenant_id, m.workspace_id, 1, current_timestamp
    FROM tenant_management.workspace_membership m
    JOIN tenant_management.workspace w ON w.id = m.workspace_id
    WHERE m.id = membership_uuid
    ON CONFLICT (membership_id) DO UPDATE
        SET authorization_version = tenant_management.membership_authorization_state.authorization_version + 1,
            updated_at = current_timestamp;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.bump_membership_role_authorization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM tenant_management.bump_authorization_membership(COALESCE(NEW.membership_id, OLD.membership_id));
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS membership_role_definition_authorization_version
    ON tenant_management.membership_role_definition;
CREATE TRIGGER membership_role_definition_authorization_version
    AFTER INSERT OR UPDATE OR DELETE ON tenant_management.membership_role_definition
    FOR EACH ROW EXECUTE FUNCTION tenant_management.bump_membership_role_authorization();
