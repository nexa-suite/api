-- Supports bounded cleanup and expiry lookup for ephemeral Delivery handoffs.
CREATE INDEX ix_handoff_token_expiry
    ON logistics.delivery_handoff_token (tenant_id, workspace_id, expires_at);
