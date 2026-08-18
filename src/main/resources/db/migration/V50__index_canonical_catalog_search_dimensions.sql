CREATE INDEX IF NOT EXISTS ix_product_family_brand_search
    ON catalog_management.product_family (tenant_id, workspace_id, brand_id, status, name, id);
CREATE INDEX IF NOT EXISTS ix_product_family_category_search
    ON catalog_management.product_family (tenant_id, workspace_id, category_id, status, name, id);
CREATE INDEX IF NOT EXISTS ix_sellable_sku_code_presentation_search
    ON catalog_management.sellable_sku (tenant_id, workspace_id, sku_code, presentation, gtin, family_id, id);
