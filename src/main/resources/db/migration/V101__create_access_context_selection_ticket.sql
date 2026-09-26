CREATE TABLE iam.access_context_selection_ticket (
	ticket_hash CHAR(64) PRIMARY KEY,
	user_id UUID NOT NULL REFERENCES iam.user_account(id),
	surface VARCHAR(32) NOT NULL CHECK (surface IN ('PLATFORM', 'PORTAL')),
	issued_at TIMESTAMPTZ NOT NULL,
	expires_at TIMESTAMPTZ NOT NULL,
	consumed_at TIMESTAMPTZ,
	revoked_at TIMESTAMPTZ,
	CONSTRAINT ck_access_context_selection_ticket_ttl
		CHECK (expires_at = issued_at + INTERVAL '5 minutes')
);

ALTER TABLE iam.access_context_selection_ticket
	ADD CONSTRAINT ck_access_context_selection_ticket_terminal_state
	CHECK (consumed_at IS NULL OR revoked_at IS NULL);

CREATE INDEX ix_access_context_selection_ticket_pending_expiry
	ON iam.access_context_selection_ticket (expires_at)
	WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_access_context_selection_ticket_terminal_retention
	ON iam.access_context_selection_ticket (coalesce(consumed_at, revoked_at, expires_at));
