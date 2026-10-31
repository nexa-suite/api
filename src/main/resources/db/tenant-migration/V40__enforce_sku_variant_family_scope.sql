DO $$
DECLARE
    invalid_link RECORD;
BEGIN
    SELECT sku.tenant_id,
           sku.workspace_id,
           sku.id AS sku_id,
           sku.family_id,
           sku.variant_id,
           variant.family_id AS variant_family_id
      INTO invalid_link
      FROM catalog_management.sellable_sku sku
      LEFT JOIN catalog_management.product_variant variant
        ON variant.tenant_id = sku.tenant_id
       AND variant.workspace_id = sku.workspace_id
       AND variant.id = sku.variant_id
     WHERE sku.variant_id IS NOT NULL
       AND (variant.id IS NULL OR variant.family_id IS DISTINCT FROM sku.family_id)
     ORDER BY sku.tenant_id, sku.workspace_id, sku.id
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'Tenant V40 cannot enforce SKU variant family scope: tenant_id %, workspace_id %, sku_id %, family_id %, variant_id %, variant_family_id %',
            invalid_link.tenant_id,
            invalid_link.workspace_id,
            invalid_link.sku_id,
            invalid_link.family_id,
            invalid_link.variant_id,
            invalid_link.variant_family_id;
    END IF;
END;
$$;

ALTER TABLE catalog_management.product_variant
    ADD CONSTRAINT uq_product_variant_scope_family_id
    UNIQUE (tenant_id, workspace_id, family_id, id);

ALTER TABLE catalog_management.sellable_sku
    ADD CONSTRAINT fk_sellable_sku_variant_family_scope
    FOREIGN KEY (tenant_id, workspace_id, family_id, variant_id)
    REFERENCES catalog_management.product_variant (tenant_id, workspace_id, family_id, id)
    MATCH SIMPLE;
