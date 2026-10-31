-- Security Audit is append-only evidence. The runtime may append facts but
-- cannot read or mutate the evidence it produces.
DO $$
BEGIN
    IF current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V176 must run in the direct nexa_migrator session';
    END IF;

    IF NOT has_function_privilege(current_user,
            'tenant_management.apply_v176_security_audit_runtime_acl()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V176 requires the one-shot Security Audit ACL helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v176_security_audit_runtime_acl();
