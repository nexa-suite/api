-- Inbox receipts are scoped facts. The runtime may inspect and append only
-- receipts for the explicit Tenant/Workspace bound to its transaction.
DO $$
BEGIN
    IF current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V177 must run in the direct nexa_migrator session';
    END IF;

    IF NOT has_function_privilege(current_user,
            'tenant_management.apply_v177_inbox_event_rls()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V177 requires the one-shot inbox RLS helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v177_inbox_event_rls();
