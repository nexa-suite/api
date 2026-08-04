CREATE TABLE IF NOT EXISTS catalog_management.product_family (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    family_code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    category_id UUID NOT NULL,
    brand_id UUID NOT NULL,
    country_of_origin CHAR(2),
    manufacturer_reference VARCHAR(160),
    supplier_reference VARCHAR(160),
    storage_family VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_product_family_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_product_family_code UNIQUE (tenant_id, workspace_id, family_code),
    CONSTRAINT fk_product_family_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_product_family_category FOREIGN KEY (tenant_id, workspace_id, category_id)
        REFERENCES catalog_management.category (tenant_id, workspace_id, id),
    CONSTRAINT fk_product_family_brand FOREIGN KEY (tenant_id, workspace_id, brand_id)
        REFERENCES catalog_management.brand (tenant_id, workspace_id, id),
    CONSTRAINT ck_product_family_status CHECK (status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT ck_product_family_storage CHECK (storage_family IN ('AMBIENT','REFRIGERATED','FROZEN')),
    CONSTRAINT ck_product_family_country CHECK (country_of_origin IS NULL OR country_of_origin ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_product_family_name CHECK (length(btrim(name)) BETWEEN 1 AND 200)
);

CREATE TABLE IF NOT EXISTS catalog_management.sellable_sku (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    family_id UUID NOT NULL,
    legacy_product_id UUID,
    legacy_catalog_item_id VARCHAR(64),
    sku_code VARCHAR(80) NOT NULL,
    gtin VARCHAR(14),
    presentation VARCHAR(160) NOT NULL,
    packaging_type VARCHAR(64) NOT NULL,
    unit_of_measure VARCHAR(32) NOT NULL,
    net_weight NUMERIC(19,4),
    gross_weight NUMERIC(19,4),
    pack_quantity NUMERIC(19,4) NOT NULL DEFAULT 1,
    dimension_length_cm NUMERIC(12,4),
    dimension_width_cm NUMERIC(12,4),
    dimension_height_cm NUMERIC(12,4),
    temperature_min NUMERIC(9,4),
    temperature_max NUMERIC(9,4),
    shelf_life_days INTEGER,
    minimum_remaining_shelf_life_days INTEGER NOT NULL DEFAULT 0,
    lot_tracking_required BOOLEAN NOT NULL DEFAULT TRUE,
    expiry_tracking_required BOOLEAN NOT NULL DEFAULT TRUE,
    tax_category VARCHAR(64) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_sellable_sku_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_sellable_sku_code UNIQUE (tenant_id, workspace_id, sku_code),
    CONSTRAINT uq_sellable_sku_legacy_item UNIQUE (tenant_id, workspace_id, legacy_catalog_item_id),
    CONSTRAINT fk_sellable_sku_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_sellable_sku_family FOREIGN KEY (tenant_id, workspace_id, family_id)
        REFERENCES catalog_management.product_family (tenant_id, workspace_id, id),
    CONSTRAINT fk_sellable_sku_legacy_product FOREIGN KEY (tenant_id, workspace_id, legacy_product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id),
    CONSTRAINT ck_sellable_sku_status CHECK (status IN ('DRAFT','ACTIVE','INACTIVE','DISCONTINUED','ARCHIVED')),
    CONSTRAINT ck_sellable_sku_gtin CHECK (gtin IS NULL OR gtin ~ '^[0-9]{8,14}$'),
    CONSTRAINT ck_sellable_sku_weights CHECK (net_weight IS NULL OR net_weight > 0),
    CONSTRAINT ck_sellable_sku_gross_weight CHECK (gross_weight IS NULL OR gross_weight > 0),
    CONSTRAINT ck_sellable_sku_pack_quantity CHECK (pack_quantity > 0),
    CONSTRAINT ck_sellable_sku_temperature CHECK (temperature_min IS NULL OR temperature_max IS NULL OR temperature_min <= temperature_max),
    CONSTRAINT ck_sellable_sku_shelf_life CHECK (shelf_life_days IS NULL OR shelf_life_days >= 0),
    CONSTRAINT ck_sellable_sku_remaining_shelf_life CHECK (minimum_remaining_shelf_life_days >= 0),
    CONSTRAINT ck_sellable_sku_dimensions CHECK ((dimension_length_cm IS NULL AND dimension_width_cm IS NULL AND dimension_height_cm IS NULL)
        OR (dimension_length_cm > 0 AND dimension_width_cm > 0 AND dimension_height_cm > 0))
);

CREATE TABLE IF NOT EXISTS catalog_management.sku_price (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    source_code VARCHAR(80),
    source_description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_sku_price_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_sku_price_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_sku_price_amount CHECK (amount >= 0),
    CONSTRAINT ck_sku_price_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_sku_price_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_sku_price_cancelled CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);

ALTER TABLE catalog_management.sku_price
    DROP CONSTRAINT IF EXISTS ex_sku_price_no_overlap;
ALTER TABLE catalog_management.sku_price
    ADD CONSTRAINT ex_sku_price_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, workspace_id WITH =, sku_id WITH =, currency WITH =,
        tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (cancelled_at IS NULL);

CREATE TABLE IF NOT EXISTS catalog_management.promotion_sku (
    promotion_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    PRIMARY KEY (promotion_id, sku_id),
    CONSTRAINT fk_promotion_sku_promotion FOREIGN KEY (tenant_id, workspace_id, promotion_id)
        REFERENCES catalog_management.promotion (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_sku_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_product_family_search ON catalog_management.product_family
    (tenant_id, workspace_id, status, name, family_code, id);
CREATE INDEX IF NOT EXISTS ix_sellable_sku_search ON catalog_management.sellable_sku
    (tenant_id, workspace_id, status, visible, sku_code, gtin, presentation, id);
CREATE INDEX IF NOT EXISTS ix_sellable_sku_family ON catalog_management.sellable_sku
    (tenant_id, workspace_id, family_id, status, presentation, id);
CREATE INDEX IF NOT EXISTS ix_sku_price_current ON catalog_management.sku_price
    (tenant_id, workspace_id, sku_id, valid_from DESC, valid_until);
CREATE INDEX IF NOT EXISTS ix_promotion_sku_scope ON catalog_management.promotion_sku
    (tenant_id, workspace_id, sku_id, promotion_id);

-- Historical products keep their UUID as the sellable SKU UUID. The seed mapping
-- remains the explicit authority for later family consolidation.
INSERT INTO catalog_management.product_family
    (id, tenant_id, workspace_id, family_code, name, description, category_id, brand_id,
     storage_family, status, created_at, updated_at)
SELECT md5(p.tenant_id::text || ':' || p.workspace_id::text || ':family:' || p.catalog_item_id)::uuid,
       p.tenant_id, p.workspace_id, 'FAM-' || p.catalog_item_id, p.name, p.description,
       p.category_id, p.brand_id, p.storage_temperature,
       CASE p.status WHEN 'DISCONTINUED' THEN 'ARCHIVED' ELSE p.status END,
       p.created_at, p.updated_at
FROM catalog_management.product p
ON CONFLICT (tenant_id, workspace_id, family_code) DO NOTHING;

INSERT INTO catalog_management.sellable_sku
    (id, tenant_id, workspace_id, family_id, legacy_product_id, legacy_catalog_item_id,
     sku_code, presentation, packaging_type, unit_of_measure, net_weight, pack_quantity,
     status, visible, version, created_at, updated_at)
SELECT p.id, p.tenant_id, p.workspace_id,
       md5(p.tenant_id::text || ':' || p.workspace_id::text || ':family:' || p.catalog_item_id)::uuid,
       p.id, p.catalog_item_id, p.product_code, coalesce(pp.presentation, p.name), 'UNSPECIFIED',
       coalesce(pp.unit_of_measure, 'UNIT'), pp.net_weight, 1, p.status,
       coalesce(pv.buyer_visible, TRUE), p.version, p.created_at, p.updated_at
FROM catalog_management.product p
LEFT JOIN catalog_management.product_presentation pp ON pp.product_id = p.id
LEFT JOIN catalog_management.product_visibility pv ON pv.product_id = p.id
ON CONFLICT (tenant_id, workspace_id, sku_code) DO NOTHING;

-- Explicit accepted family consolidation for the canonical Gouda Natural example.
UPDATE catalog_management.product_family family
SET name = 'QUESO GOUDA NATURAL', updated_at = current_timestamp
WHERE family.id = (
    SELECT s.family_id FROM catalog_management.sellable_sku s
    WHERE s.tenant_id = family.tenant_id AND s.workspace_id = family.workspace_id
      AND s.legacy_catalog_item_id = 'CAT-0022'
);
UPDATE catalog_management.sellable_sku target
SET family_id = source.family_id, updated_at = current_timestamp
FROM catalog_management.sellable_sku source
WHERE target.tenant_id = source.tenant_id AND target.workspace_id = source.workspace_id
  AND source.legacy_catalog_item_id = 'CAT-0022'
  AND target.legacy_catalog_item_id = 'CAT-0023';

INSERT INTO catalog_management.sku_price
    (id, tenant_id, workspace_id, sku_id, amount, currency, valid_from, valid_until,
     source_code, source_description, version, created_at, cancelled_at)
SELECT pp.id, pp.tenant_id, pp.workspace_id, pp.product_id, pp.amount, pp.currency,
       pp.valid_from, pp.valid_until, pp.source_code, pp.source_description, pp.version,
       pp.created_at, pp.cancelled_at
FROM catalog_management.product_price pp
JOIN catalog_management.sellable_sku s
  ON s.tenant_id = pp.tenant_id AND s.workspace_id = pp.workspace_id AND s.id = pp.product_id
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog_management.promotion_sku (promotion_id, tenant_id, workspace_id, sku_id)
SELECT promotion_id, tenant_id, workspace_id, product_id
FROM catalog_management.promotion_product
ON CONFLICT DO NOTHING;

ALTER TABLE warehouse.inventory_lot ADD COLUMN IF NOT EXISTS sku_id UUID;
ALTER TABLE warehouse.stock_movement ADD COLUMN IF NOT EXISTS sku_id UUID;
ALTER TABLE sales.purchase_request_line ADD COLUMN IF NOT EXISTS sku_id UUID;
ALTER TABLE sales.sales_order_line ADD COLUMN IF NOT EXISTS sku_id UUID;

UPDATE warehouse.inventory_lot l SET sku_id = s.id
FROM catalog_management.sellable_sku s
WHERE l.sku_id IS NULL AND s.tenant_id = l.tenant_id AND s.workspace_id = l.workspace_id
  AND s.legacy_catalog_item_id = l.catalog_item_id;
UPDATE warehouse.stock_movement m SET sku_id = s.id
FROM catalog_management.sellable_sku s
WHERE m.sku_id IS NULL AND s.tenant_id = m.tenant_id AND s.workspace_id = m.workspace_id
  AND s.legacy_catalog_item_id = m.catalog_item_id;
UPDATE sales.purchase_request_line l SET sku_id = s.id
FROM catalog_management.sellable_sku s
WHERE l.sku_id IS NULL AND s.tenant_id = (SELECT tenant_id FROM sales.purchase_request r WHERE r.id = l.purchase_request_id)
  AND s.legacy_catalog_item_id = l.catalog_item_id;
UPDATE sales.sales_order_line l SET sku_id = s.id
FROM catalog_management.sellable_sku s
     , sales.sales_order o
WHERE o.id = l.sales_order_id AND l.sku_id IS NULL AND s.tenant_id = o.tenant_id AND s.workspace_id = o.workspace_id
  AND s.legacy_catalog_item_id = l.catalog_item_id;

ALTER TABLE warehouse.inventory_lot
    ADD CONSTRAINT fk_inventory_lot_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id);
ALTER TABLE warehouse.stock_movement
    ADD CONSTRAINT fk_stock_movement_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id);
ALTER TABLE sales.purchase_request_line
    ADD CONSTRAINT fk_purchase_request_line_sku FOREIGN KEY (sku_id)
        REFERENCES catalog_management.sellable_sku (id);
ALTER TABLE sales.sales_order_line
    ADD CONSTRAINT fk_sales_order_line_sku FOREIGN KEY (sku_id)
        REFERENCES catalog_management.sellable_sku (id);
