-- Notification recipient projection must exclude inactive Buyer relationships.
-- Grant only the status column needed by the existing scoped relationship query.
GRANT SELECT (status) ON TABLE sales.client_account_membership TO nexa_business_traceability_worker;
