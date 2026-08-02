CREATE SCHEMA IF NOT EXISTS catalog_management;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE catalog_management.category (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    parent_category_id UUID,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_catalog_category_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_catalog_category_slug UNIQUE (tenant_id, workspace_id, slug),
    CONSTRAINT fk_catalog_category_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_catalog_category_parent
        FOREIGN KEY (tenant_id, workspace_id, parent_category_id)
        REFERENCES catalog_management.category (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_category_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_catalog_category_name CHECK (length(btrim(name)) BETWEEN 1 AND 160),
    CONSTRAINT ck_catalog_category_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE catalog_management.brand (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_catalog_brand_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_catalog_brand_slug UNIQUE (tenant_id, workspace_id, slug),
    CONSTRAINT fk_catalog_brand_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_catalog_brand_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_catalog_brand_name CHECK (length(btrim(name)) BETWEEN 1 AND 160),
    CONSTRAINT ck_catalog_brand_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE catalog_management.product (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    slug VARCHAR(140) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    category_id UUID NOT NULL,
    brand_id UUID NOT NULL,
    storage_temperature VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_catalog_product_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_catalog_product_item_id UNIQUE (tenant_id, workspace_id, catalog_item_id),
    CONSTRAINT uq_catalog_product_code UNIQUE (tenant_id, workspace_id, product_code),
    CONSTRAINT uq_catalog_product_slug UNIQUE (tenant_id, workspace_id, slug),
    CONSTRAINT fk_catalog_product_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_catalog_product_category
        FOREIGN KEY (tenant_id, workspace_id, category_id)
        REFERENCES catalog_management.category (tenant_id, workspace_id, id),
    CONSTRAINT fk_catalog_product_brand
        FOREIGN KEY (tenant_id, workspace_id, brand_id)
        REFERENCES catalog_management.brand (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DISCONTINUED', 'ARCHIVED')),
    CONSTRAINT ck_catalog_product_temperature CHECK (storage_temperature IN ('AMBIENT', 'REFRIGERATED', 'FROZEN')),
    CONSTRAINT ck_catalog_product_name CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT ck_catalog_product_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE catalog_management.product_presentation (
    product_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    presentation VARCHAR(160) NOT NULL,
    unit_of_measure VARCHAR(32) NOT NULL DEFAULT 'UNIT',
    net_weight NUMERIC(19, 4),
    weight_unit VARCHAR(16),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_catalog_presentation_product
        FOREIGN KEY (tenant_id, workspace_id, product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_presentation_weight CHECK (net_weight IS NULL OR net_weight > 0),
    CONSTRAINT ck_catalog_presentation_unit CHECK (length(btrim(unit_of_measure)) BETWEEN 1 AND 32)
);

CREATE TABLE catalog_management.product_asset_reference (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    product_id UUID NOT NULL,
    asset_path VARCHAR(512) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    alt_text VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_catalog_asset_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_catalog_asset_product_path UNIQUE (tenant_id, workspace_id, product_id, asset_path),
    CONSTRAINT fk_catalog_asset_product
        FOREIGN KEY (tenant_id, workspace_id, product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_asset_path CHECK (asset_path LIKE '/catalog-items/%' AND asset_path NOT LIKE '%..%'),
    CONSTRAINT ck_catalog_asset_order CHECK (sort_order BETWEEN 0 AND 1000)
);

CREATE TABLE catalog_management.product_visibility (
    product_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_visible BOOLEAN NOT NULL DEFAULT FALSE,
    sales_visible BOOLEAN NOT NULL DEFAULT TRUE,
    warehouse_visible BOOLEAN NOT NULL DEFAULT TRUE,
    logistics_visible BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_catalog_visibility_product
        FOREIGN KEY (tenant_id, workspace_id, product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id)
);

CREATE TABLE catalog_management.product_price (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    product_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    source_code VARCHAR(80),
    source_description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_catalog_price_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_catalog_price_product
        FOREIGN KEY (tenant_id, workspace_id, product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_price_amount CHECK (amount >= 0),
    CONSTRAINT ck_catalog_price_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_catalog_price_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_catalog_price_cancelled CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);
ALTER TABLE catalog_management.product_price
    ADD CONSTRAINT ex_catalog_price_no_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        workspace_id WITH =,
        product_id WITH =,
        currency WITH =,
        tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (cancelled_at IS NULL);

CREATE TABLE catalog_management.promotion (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    slug VARCHAR(140) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    discount_type VARCHAR(16) NOT NULL,
    discount_value NUMERIC(19, 4) NOT NULL,
    currency CHAR(3),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    minimum_quantity NUMERIC(19, 4) NOT NULL DEFAULT 1,
    stacking_policy VARCHAR(16) NOT NULL DEFAULT 'EXCLUSIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_catalog_promotion_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_catalog_promotion_slug UNIQUE (tenant_id, workspace_id, slug),
    CONSTRAINT fk_catalog_promotion_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_catalog_promotion_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_catalog_promotion_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT ck_catalog_promotion_discount_value CHECK (
        discount_value >= 0 AND (discount_type <> 'PERCENTAGE' OR discount_value <= 100)
    ),
    CONSTRAINT ck_catalog_promotion_currency CHECK (
        (discount_type = 'PERCENTAGE' AND currency IS NULL) OR
        (discount_type = 'FIXED_AMOUNT' AND currency ~ '^[A-Z]{3}$')
    ),
    CONSTRAINT ck_catalog_promotion_period CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_catalog_promotion_quantity CHECK (minimum_quantity > 0),
    CONSTRAINT ck_catalog_promotion_stacking CHECK (stacking_policy IN ('EXCLUSIVE', 'STACKABLE'))
);

CREATE TABLE catalog_management.promotion_product (
    promotion_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    product_id UUID NOT NULL,
    PRIMARY KEY (promotion_id, product_id),
    CONSTRAINT fk_catalog_promotion_product_promotion
        FOREIGN KEY (tenant_id, workspace_id, promotion_id)
        REFERENCES catalog_management.promotion (tenant_id, workspace_id, id),
    CONSTRAINT fk_catalog_promotion_product_product
        FOREIGN KEY (tenant_id, workspace_id, product_id)
        REFERENCES catalog_management.product (tenant_id, workspace_id, id)
);

CREATE TABLE catalog_management.promotion_category (
    promotion_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    category_id UUID NOT NULL,
    PRIMARY KEY (promotion_id, category_id),
    CONSTRAINT fk_catalog_promotion_category_promotion
        FOREIGN KEY (tenant_id, workspace_id, promotion_id)
        REFERENCES catalog_management.promotion (tenant_id, workspace_id, id),
    CONSTRAINT fk_catalog_promotion_category_category
        FOREIGN KEY (tenant_id, workspace_id, category_id)
        REFERENCES catalog_management.category (tenant_id, workspace_id, id)
);

CREATE TABLE catalog_management.promotion_client_account (
    promotion_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    PRIMARY KEY (promotion_id, client_account_id),
    CONSTRAINT fk_catalog_promotion_client_promotion
        FOREIGN KEY (tenant_id, workspace_id, promotion_id)
        REFERENCES catalog_management.promotion (tenant_id, workspace_id, id),
    CONSTRAINT fk_catalog_promotion_client_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id)
);

CREATE TABLE catalog_management.promotion_rule (
    id UUID PRIMARY KEY,
    promotion_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    rule_value VARCHAR(255) NOT NULL,
    CONSTRAINT uq_catalog_promotion_rule UNIQUE (promotion_id, rule_type, rule_value),
    CONSTRAINT fk_catalog_promotion_rule_promotion
        FOREIGN KEY (tenant_id, workspace_id, promotion_id)
        REFERENCES catalog_management.promotion (tenant_id, workspace_id, id),
    CONSTRAINT ck_catalog_promotion_rule_type CHECK (rule_type IN ('MIN_ORDER_AMOUNT', 'CLIENT_SEGMENT', 'BUYER_TIER', 'CURRENCY'))
);

CREATE INDEX ix_catalog_category_list ON catalog_management.category (tenant_id, workspace_id, status, name, id);
CREATE INDEX ix_catalog_brand_list ON catalog_management.brand (tenant_id, workspace_id, status, name, id);
CREATE INDEX ix_catalog_product_list ON catalog_management.product (tenant_id, workspace_id, status, name, id);
CREATE INDEX ix_catalog_product_category ON catalog_management.product (tenant_id, workspace_id, category_id, status, name);
CREATE INDEX ix_catalog_product_brand ON catalog_management.product (tenant_id, workspace_id, brand_id, status, name);
CREATE INDEX ix_catalog_asset_product ON catalog_management.product_asset_reference (tenant_id, workspace_id, product_id, sort_order);
CREATE INDEX ix_catalog_price_current ON catalog_management.product_price (tenant_id, workspace_id, product_id, valid_from DESC, valid_until);
CREATE INDEX ix_catalog_promotion_state ON catalog_management.promotion (tenant_id, workspace_id, status, starts_at, ends_at);
CREATE INDEX ix_catalog_promotion_product ON catalog_management.promotion_product (tenant_id, workspace_id, product_id, promotion_id);
CREATE INDEX ix_catalog_promotion_category ON catalog_management.promotion_category (tenant_id, workspace_id, category_id, promotion_id);
CREATE INDEX ix_catalog_promotion_client ON catalog_management.promotion_client_account (tenant_id, workspace_id, client_account_id, promotion_id);
