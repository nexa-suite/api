-- The reviewed JSON artifact is the source of truth for this mapping.  Keep
-- this SQL copy deterministic for upgrade/fresh database execution; runtime
-- code must not infer family relationships from names or codes.
CREATE TEMP TABLE catalog_family_sku_mapping (
    legacy_catalog_item_id VARCHAR(64) PRIMARY KEY,
    family_code VARCHAR(80) NOT NULL,
    sku_code VARCHAR(80) NOT NULL
) ON COMMIT DROP;

INSERT INTO catalog_family_sku_mapping (legacy_catalog_item_id, family_code, sku_code) VALUES
    ('CAT-0001', 'FAM-CAT-0001', 'PROD-0001'),
    ('CAT-0002', 'FAM-CAT-0002', 'PROD-0002'),
    ('CAT-0003', 'FAM-CAT-0003', 'PROD-0003'),
    ('CAT-0004', 'FAM-CAT-0004', 'PROD-0004'),
    ('CAT-0005', 'FAM-CAT-0005', 'PROD-0005'),
    ('CAT-0006', 'FAM-CAT-0006', 'PROD-0006'),
    ('CAT-0007', 'FAM-CAT-0007', 'PROD-0007'),
    ('CAT-0008', 'FAM-CAT-0008', 'PROD-0008'),
    ('CAT-0009', 'FAM-CAT-0009', 'PROD-0009'),
    ('CAT-0010', 'FAM-CAT-0010', 'PROD-0010'),
    ('CAT-0011', 'FAM-CAT-0011', 'PROD-0011'),
    ('CAT-0012', 'FAM-CAT-0012', 'PROD-0012'),
    ('CAT-0013', 'FAM-CAT-0013', 'PROD-0013'),
    ('CAT-0014', 'FAM-CAT-0014', 'PROD-0014'),
    ('CAT-0015', 'FAM-CAT-0015', 'PROD-0015'),
    ('CAT-0016', 'FAM-CAT-0016', 'PROD-0016'),
    ('CAT-0017', 'FAM-CAT-0017', 'PROD-0017'),
    ('CAT-0018', 'FAM-CAT-0018', 'PROD-0018'),
    ('CAT-0019', 'FAM-CAT-0019', 'PROD-0019'),
    ('CAT-0020', 'FAM-CAT-0020', 'PROD-0020'),
    ('CAT-0021', 'FAM-CAT-0021', 'PROD-0021'),
    ('CAT-0022', 'FAM-CAT-0022', 'PROD-0022'),
    ('CAT-0023', 'FAM-CAT-0022', 'PROD-0023'),
    ('CAT-0024', 'FAM-CAT-0024', 'PROD-0024'),
    ('CAT-0025', 'FAM-CAT-0025', 'PROD-0025'),
    ('CAT-0026', 'FAM-CAT-0026', 'PROD-0026'),
    ('CAT-0027', 'FAM-CAT-0027', 'PROD-0027'),
    ('CAT-0028', 'FAM-CAT-0028', 'PROD-0028'),
    ('CAT-0029', 'FAM-CAT-0029', 'PROD-0029'),
    ('CAT-0030', 'FAM-CAT-0030', 'PROD-0030'),
    ('CAT-0031', 'FAM-CAT-0031', 'PROD-0031'),
    ('CAT-0032', 'FAM-CAT-0032', 'PROD-0032'),
    ('CAT-0033', 'FAM-CAT-0033', 'PROD-0033'),
    ('CAT-0034', 'FAM-CAT-0034', 'PROD-0034'),
    ('CAT-0035', 'FAM-CAT-0035', 'PROD-0035'),
    ('CAT-0036', 'FAM-CAT-0036', 'PROD-0036'),
    ('CAT-0037', 'FAM-CAT-0037', 'PROD-0037'),
    ('CAT-0038', 'FAM-CAT-0038', 'PROD-0038'),
    ('CAT-0039', 'FAM-CAT-0039', 'PROD-0039'),
    ('CAT-0040', 'FAM-CAT-0040', 'PROD-0040'),
    ('CAT-0041', 'FAM-CAT-0041', 'PROD-0041'),
    ('CAT-0042', 'FAM-CAT-0042', 'PROD-0042'),
    ('CAT-0043', 'FAM-CAT-0043', 'PROD-0043'),
    ('CAT-0044', 'FAM-CAT-0044', 'PROD-0044'),
    ('CAT-0045', 'FAM-CAT-0045', 'PROD-0045'),
    ('CAT-0046', 'FAM-CAT-0046', 'PROD-0046'),
    ('CAT-0047', 'FAM-CAT-0047', 'PROD-0047'),
    ('CAT-0048', 'FAM-CAT-0048', 'PROD-0048'),
    ('CAT-0049', 'FAM-CAT-0049', 'PROD-0049'),
    ('CAT-0050', 'FAM-CAT-0050', 'PROD-0050');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM catalog_management.product p
        LEFT JOIN catalog_family_sku_mapping m ON m.legacy_catalog_item_id = p.catalog_item_id
        WHERE m.legacy_catalog_item_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Explicit catalog family/SKU mapping is incomplete';
    END IF;
END $$;

UPDATE catalog_management.sellable_sku s
SET family_id = f.id,
    sku_code = m.sku_code,
    updated_at = current_timestamp
FROM catalog_family_sku_mapping m,
     catalog_management.product_family f
WHERE s.legacy_catalog_item_id = m.legacy_catalog_item_id
  AND f.tenant_id = s.tenant_id
  AND f.workspace_id = s.workspace_id
  AND f.family_code = m.family_code;

CREATE INDEX IF NOT EXISTS ix_sellable_sku_legacy_mapping
    ON catalog_management.sellable_sku (tenant_id, workspace_id, legacy_catalog_item_id);
