ALTER TABLE sales.purchase_request_line
    ADD COLUMN IF NOT EXISTS product_family_id UUID,
    ADD COLUMN IF NOT EXISTS product_family_code_snapshot VARCHAR(80),
    ADD COLUMN IF NOT EXISTS sku_code_snapshot VARCHAR(80);

ALTER TABLE sales.sales_order_line
    ADD COLUMN IF NOT EXISTS product_family_id UUID,
    ADD COLUMN IF NOT EXISTS product_family_code_snapshot VARCHAR(80),
    ADD COLUMN IF NOT EXISTS sku_code_snapshot VARCHAR(80);

UPDATE sales.purchase_request_line l
SET product_family_id = s.family_id,
    product_family_code_snapshot = f.family_code,
    sku_code_snapshot = s.sku_code
FROM catalog_management.sellable_sku s
JOIN catalog_management.product_family f ON f.id = s.family_id
WHERE l.sku_id = s.id
  AND l.product_family_id IS NULL;

UPDATE sales.sales_order_line l
SET product_family_id = s.family_id,
    product_family_code_snapshot = f.family_code,
    sku_code_snapshot = s.sku_code
FROM catalog_management.sellable_sku s
JOIN catalog_management.product_family f ON f.id = s.family_id
WHERE l.sku_id = s.id
  AND l.product_family_id IS NULL;

ALTER TABLE sales.purchase_request_line
    ADD CONSTRAINT fk_purchase_request_line_family
        FOREIGN KEY (product_family_id) REFERENCES catalog_management.product_family(id);
ALTER TABLE sales.sales_order_line
    ADD CONSTRAINT fk_sales_order_line_family
        FOREIGN KEY (product_family_id) REFERENCES catalog_management.product_family(id);

CREATE INDEX IF NOT EXISTS ix_purchase_request_line_sku_snapshot
    ON sales.purchase_request_line (purchase_request_id, sku_id, product_family_id);
CREATE INDEX IF NOT EXISTS ix_sales_order_line_sku_snapshot
    ON sales.sales_order_line (sales_order_id, sku_id, product_family_id);
