-- PostgreSQL reads the conflict target during INSERT ... ON CONFLICT.
-- Keep access limited to the inbox uniqueness columns and existing scoped RLS.
GRANT SELECT (event_id, recipient_membership_id)
    ON notifications.inbox_item TO nexa_business_traceability_worker;
