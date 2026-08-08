CREATE TABLE IF NOT EXISTS catalog_management.product_variant (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    family_id UUID NOT NULL,
    variant_code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(4000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_product_variant_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_product_variant_code UNIQUE (tenant_id, workspace_id, variant_code),
    CONSTRAINT fk_product_variant_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_product_variant_family FOREIGN KEY (tenant_id, workspace_id, family_id)
        REFERENCES catalog_management.product_family (tenant_id, workspace_id, id),
    CONSTRAINT ck_product_variant_status CHECK (status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT ck_product_variant_name CHECK (length(btrim(name)) BETWEEN 1 AND 200)
);

ALTER TABLE catalog_management.sellable_sku
    ADD COLUMN IF NOT EXISTS variant_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_sellable_sku_variant'
    ) THEN
        ALTER TABLE catalog_management.sellable_sku
            ADD CONSTRAINT fk_sellable_sku_variant
            FOREIGN KEY (tenant_id, workspace_id, variant_id)
            REFERENCES catalog_management.product_variant (tenant_id, workspace_id, id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_product_variant_family
    ON catalog_management.product_variant (tenant_id, workspace_id, family_id, status, name, id);
CREATE INDEX IF NOT EXISTS ix_sellable_sku_variant
    ON catalog_management.sellable_sku (tenant_id, workspace_id, variant_id, status, presentation, id);

-- Reviewed V1 semantics for the Gouda hierarchy.  The mapping is explicit and
-- stable; runtime code must not derive variants from display-name heuristics.
CREATE TEMP TABLE catalog_gouda_variant_mapping (
    legacy_catalog_item_id VARCHAR(64) PRIMARY KEY,
    variant_code VARCHAR(80) NOT NULL,
    variant_name VARCHAR(200) NOT NULL
) ON COMMIT DROP;

INSERT INTO catalog_gouda_variant_mapping (legacy_catalog_item_id, variant_code, variant_name) VALUES
    ('CAT-0014', 'VAR-GOUDA-CABRA', 'Cabra'),
    ('CAT-0015', 'VAR-GOUDA-CABRA', 'Cabra'),
    ('CAT-0016', 'VAR-GOUDA-CHILI', 'Chili'),
    ('CAT-0017', 'VAR-GOUDA-CHILI', 'Chili'),
    ('CAT-0018', 'VAR-GOUDA-COMINO', 'Comino'),
    ('CAT-0019', 'VAR-GOUDA-COMINO', 'Comino'),
    ('CAT-0020', 'VAR-GOUDA-FINAS-HIERBAS', 'Finas Hierbas'),
    ('CAT-0021', 'VAR-GOUDA-FINAS-HIERBAS', 'Finas Hierbas'),
    ('CAT-0022', 'VAR-GOUDA-NATURAL', 'Natural'),
    ('CAT-0023', 'VAR-GOUDA-NATURAL', 'Natural'),
    ('CAT-0024', 'VAR-GOUDA-PIMIENTA', 'Pimienta'),
    ('CAT-0025', 'VAR-GOUDA-PIMIENTA', 'Pimienta');

INSERT INTO catalog_management.product_family
    (id, tenant_id, workspace_id, family_code, name, description, category_id, brand_id,
     country_of_origin, manufacturer_reference, supplier_reference, storage_family, status,
     version, created_at, updated_at)
SELECT md5(base.tenant_id::text || ':' || base.workspace_id::text || ':family:FAM-GOUDA')::uuid,
       base.tenant_id, base.workspace_id, 'FAM-GOUDA', 'QUESO GOUDA',
       'Familia comercial Gouda; las recetas y maduraciones viven como variantes.',
       base.category_id, base.brand_id, base.country_of_origin, base.manufacturer_reference,
       base.supplier_reference, base.storage_family, 'ACTIVE', 0, current_timestamp, current_timestamp
FROM catalog_management.product_family base
WHERE base.family_code = 'FAM-CAT-0022'
ON CONFLICT (tenant_id, workspace_id, family_code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    updated_at = current_timestamp;

INSERT INTO catalog_management.product_variant
    (id, tenant_id, workspace_id, family_id, variant_code, name, description, status, version, created_at, updated_at)
SELECT md5(scope.tenant_id::text || ':' || scope.workspace_id::text || ':variant:' || mapping.variant_code)::uuid,
       scope.tenant_id, scope.workspace_id,
       md5(scope.tenant_id::text || ':' || scope.workspace_id::text || ':family:FAM-GOUDA')::uuid,
       mapping.variant_code, mapping.variant_name, 'Variante comercial revisada de Queso Gouda',
       'ACTIVE', 0, current_timestamp, current_timestamp
FROM (SELECT DISTINCT tenant_id, workspace_id FROM catalog_management.product_family WHERE family_code = 'FAM-CAT-0022') scope
CROSS JOIN (SELECT DISTINCT variant_code, variant_name FROM catalog_gouda_variant_mapping) mapping
ON CONFLICT (tenant_id, workspace_id, variant_code) DO UPDATE SET
    name = EXCLUDED.name,
    family_id = EXCLUDED.family_id,
    updated_at = current_timestamp;

UPDATE catalog_management.sellable_sku sku
SET family_id = family.id,
    variant_id = variant.id,
    updated_at = current_timestamp
FROM catalog_gouda_variant_mapping mapping,
     catalog_management.product_family family,
     catalog_management.product_variant variant
WHERE sku.legacy_catalog_item_id = mapping.legacy_catalog_item_id
  AND family.tenant_id = sku.tenant_id
  AND family.workspace_id = sku.workspace_id
  AND family.family_code = 'FAM-GOUDA'
  AND variant.tenant_id = sku.tenant_id
  AND variant.workspace_id = sku.workspace_id
  AND variant.family_id = family.id
  AND variant.variant_code = mapping.variant_code;

UPDATE catalog_management.product_family old_family
SET status = 'ARCHIVED', updated_at = current_timestamp
WHERE old_family.family_code IN ('FAM-CAT-0014','FAM-CAT-0016','FAM-CAT-0018','FAM-CAT-0020','FAM-CAT-0022','FAM-CAT-0024')
  AND NOT EXISTS (
      SELECT 1 FROM catalog_management.sellable_sku sku
      WHERE sku.tenant_id = old_family.tenant_id
        AND sku.workspace_id = old_family.workspace_id
        AND sku.family_id = old_family.id
  );
