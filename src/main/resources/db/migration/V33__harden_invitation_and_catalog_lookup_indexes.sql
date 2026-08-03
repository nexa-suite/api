CREATE INDEX ix_organization_invitation_expiration_queue
    ON tenant_management.organization_invitation (expires_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_catalog_promotion_active_scope
    ON catalog_management.promotion (tenant_id, workspace_id, status, starts_at, ends_at, id);

CREATE INDEX ix_catalog_promotion_rule_lookup
    ON catalog_management.promotion_rule (tenant_id, workspace_id, rule_type, rule_value, promotion_id);

CREATE INDEX ix_catalog_promotion_client_lookup
    ON catalog_management.promotion_client_account (tenant_id, workspace_id, promotion_id, client_account_id);
