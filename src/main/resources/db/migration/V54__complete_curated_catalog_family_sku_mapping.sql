-- The V1 catalog mapping is deliberately reviewed data, not a name heuristic.
-- Keep legacy SKU UUIDs/codes and product assets; only the family ownership and
-- family projection name are consolidated here.
CREATE TEMP TABLE catalog_family_sku_mapping_v54 (
    legacy_catalog_item_id VARCHAR(64) PRIMARY KEY,
    family_code VARCHAR(80) NOT NULL,
    family_name VARCHAR(200) NOT NULL,
    sku_code VARCHAR(80) NOT NULL,
    presentation VARCHAR(160) NOT NULL
) ON COMMIT DROP;

INSERT INTO catalog_family_sku_mapping_v54
    (legacy_catalog_item_id, family_code, family_name, sku_code, presentation)
VALUES
    ('CAT-0001', 'FAM-CAT-0001', 'QUESO GRANA PADANO DOP', 'PROD-0001', '150G'),
    ('CAT-0002', 'FAM-CAT-0002', 'QUESO PARMIGIANO REGGIANO DOP', 'PROD-0002', '150G'),
    ('CAT-0003', 'FAM-CAT-0003', 'COPPA', 'PROD-0003', 'MOLDE 3KG'),
    ('CAT-0004', 'FAM-CAT-0004', 'MORTADELLA BOLOGNA IGP CON PISTACCHIO', 'PROD-0004', 'MOLDE 7.5KG'),
    ('CAT-0005', 'FAM-CAT-0005', 'SALAME MILANO', 'PROD-0005', '100G'),
    ('CAT-0006', 'FAM-CAT-0005', 'SALAME MILANO', 'PROD-0006', 'MOLDE 2.5KG'),
    ('CAT-0007', 'FAM-CAT-0007', 'SALAME NAPOLI', 'PROD-0007', '100G'),
    ('CAT-0008', 'FAM-CAT-0007', 'SALAME NAPOLI', 'PROD-0008', 'MOLDE 1.5KG'),
    ('CAT-0009', 'FAM-CAT-0009', 'QUESO AHUMADO JAMON', 'PROD-0009', 'MOLDE 3KG'),
    ('CAT-0010', 'FAM-CAT-0010', 'QUESO AHUMADO NATURAL', 'PROD-0010', 'MOLDE 3KG'),
    ('CAT-0011', 'FAM-CAT-0011', 'QUESO AHUMADO PICANTE', 'PROD-0011', 'MOLDE 3KG'),
    ('CAT-0012', 'FAM-CAT-0012', 'QUESO EDAM BOLA', 'PROD-0012', 'CORTE'),
    ('CAT-0013', 'FAM-CAT-0012', 'QUESO EDAM BOLA', 'PROD-0013', 'MOLDE 1.9KG'),
    ('CAT-0014', 'FAM-CAT-0014', 'QUESO GOUDA CABRA', 'PROD-0014', 'CORTE'),
    ('CAT-0015', 'FAM-CAT-0014', 'QUESO GOUDA CABRA', 'PROD-0015', 'MOLDE 5KG'),
    ('CAT-0016', 'FAM-CAT-0016', 'QUESO GOUDA CHILI', 'PROD-0016', 'CORTE'),
    ('CAT-0017', 'FAM-CAT-0016', 'QUESO GOUDA CHILI', 'PROD-0017', 'MOLDE 4.5KG'),
    ('CAT-0018', 'FAM-CAT-0018', 'QUESO GOUDA COMINO', 'PROD-0018', 'CORTE'),
    ('CAT-0019', 'FAM-CAT-0018', 'QUESO GOUDA COMINO', 'PROD-0019', 'MOLDE 4.5KG'),
    ('CAT-0020', 'FAM-CAT-0020', 'QUESO GOUDA FINAS HIERBAS', 'PROD-0020', 'CORTE'),
    ('CAT-0021', 'FAM-CAT-0020', 'QUESO GOUDA FINAS HIERBAS', 'PROD-0021', 'MOLDE 4.5KG'),
    ('CAT-0022', 'FAM-CAT-0022', 'QUESO GOUDA NATURAL', 'PROD-0022', 'CORTE'),
    ('CAT-0023', 'FAM-CAT-0022', 'QUESO GOUDA NATURAL', 'PROD-0023', 'MOLDE 4.5KG'),
    ('CAT-0024', 'FAM-CAT-0024', 'QUESO GOUDA PIMIENTA', 'PROD-0024', 'CORTE'),
    ('CAT-0025', 'FAM-CAT-0024', 'QUESO GOUDA PIMIENTA', 'PROD-0025', 'MOLDE 4.5KG'),
    ('CAT-0026', 'FAM-CAT-0026', 'QUESO MAASDAM', 'PROD-0026', 'CORTE'),
    ('CAT-0027', 'FAM-CAT-0026', 'QUESO MAASDAM', 'PROD-0027', 'MOLDE 12.5KG'),
    ('CAT-0028', 'FAM-CAT-0028', 'QUESO DANISH BLUE', 'PROD-0028', '100G'),
    ('CAT-0029', 'FAM-CAT-0028', 'QUESO DANISH BLUE', 'PROD-0029', 'MOLDE 3KG'),
    ('CAT-0030', 'FAM-CAT-0030', 'QUESO SLICES CHEDDAR', 'PROD-0030', '48U.800G'),
    ('CAT-0031', 'FAM-CAT-0031', 'PROSCIUTTO CRUDO MATTONELLA', 'PROD-0031', 'MOLDE 4KG'),
    ('CAT-0032', 'FAM-CAT-0032', 'QUESO FETA DOP', 'PROD-0032', 'CORTE'),
    ('CAT-0033', 'FAM-CAT-0033', 'MANTEQUILLA CON SAL', 'PROD-0033', '20X10G'),
    ('CAT-0034', 'FAM-CAT-0034', 'MANTEQUILLA SIN SAL', 'PROD-0034', '20X10G'),
    ('CAT-0035', 'FAM-CAT-0035', 'QUESO BLEU DAUVERGNE', 'PROD-0035', '125G'),
    ('CAT-0036', 'FAM-CAT-0036', 'QUESO DE CABRA AFH', 'PROD-0036', '100G'),
    ('CAT-0037', 'FAM-CAT-0037', 'QUESO DE CABRA MIEL', 'PROD-0037', '100G'),
    ('CAT-0038', 'FAM-CAT-0038', 'QUESO DE CABRA NATURAL', 'PROD-0038', '100G'),
    ('CAT-0039', 'FAM-CAT-0039', 'QUESO DE CABRA PIMIENTA', 'PROD-0039', '100G'),
    ('CAT-0040', 'FAM-CAT-0040', 'QUESO EMMENTAL', 'PROD-0040', 'CORTE'),
    ('CAT-0041', 'FAM-CAT-0040', 'QUESO EMMENTAL', 'PROD-0041', 'MOLDE 3.5KG'),
    ('CAT-0042', 'FAM-CAT-0042', 'QUESO PETIT MOULÉ AFH', 'PROD-0042', '150G'),
    ('CAT-0043', 'FAM-CAT-0043', 'RACLETTE SLICES', 'PROD-0043', '400G'),
    ('CAT-0044', 'FAM-CAT-0044', 'QUESO MANCHEGO DOP 12 MESES', 'PROD-0044', 'CORTE'),
    ('CAT-0045', 'FAM-CAT-0045', 'QUESO MANCHEGO DOP 3 MESES', 'PROD-0045', 'CORTE'),
    ('CAT-0046', 'FAM-CAT-0045', 'QUESO MANCHEGO DOP 3 MESES', 'PROD-0046', 'MOLDE 3KG'),
    ('CAT-0047', 'FAM-CAT-0047', 'QUESO MANCHEGO DOP 6 MESES', 'PROD-0047', 'CORTE'),
    ('CAT-0048', 'FAM-CAT-0047', 'QUESO MANCHEGO DOP 6 MESES', 'PROD-0048', 'MOLDE 3KG'),
    ('CAT-0049', 'FAM-CAT-0049', 'PANNACOTTA CARAMEL', 'PROD-0049', '2X90G'),
    ('CAT-0050', 'FAM-CAT-0050', 'QUESO MASCARPONE UHT', 'PROD-0050', '500G');

DO $$
BEGIN
    IF (SELECT count(*) FROM catalog_family_sku_mapping_v54) <> 50
       OR (SELECT count(DISTINCT family_code) FROM catalog_family_sku_mapping_v54) <> 36 THEN
        RAISE EXCEPTION 'Curated catalog mapping must contain 50 SKUs and 36 Product Families';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM catalog_management.product p
        LEFT JOIN catalog_family_sku_mapping_v54 m ON m.legacy_catalog_item_id = p.catalog_item_id
        WHERE p.catalog_item_id LIKE 'CAT-%' AND m.legacy_catalog_item_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Curated catalog mapping does not cover every seeded catalog item';
    END IF;
END $$;

UPDATE catalog_management.product_family f
SET name = mapping.family_name,
    updated_at = current_timestamp
FROM (
    SELECT DISTINCT family_code, family_name
    FROM catalog_family_sku_mapping_v54
) mapping
WHERE f.family_code = mapping.family_code;

UPDATE catalog_management.sellable_sku s
SET family_id = f.id,
    sku_code = mapping.sku_code,
    presentation = mapping.presentation,
    updated_at = current_timestamp
FROM catalog_family_sku_mapping_v54 mapping
JOIN catalog_management.product_family f
  ON f.family_code = mapping.family_code
WHERE f.tenant_id = s.tenant_id
  AND f.workspace_id = s.workspace_id
  AND s.legacy_catalog_item_id = mapping.legacy_catalog_item_id;

DO $$
BEGIN
    -- Local bootstrap imports the 50 deterministic products after Flyway on a
    -- fresh database.  On an upgrade, validate the already materialized
    -- projection; on a fresh database the post-startup reconciliation owns it.
    IF EXISTS (SELECT 1 FROM catalog_management.product WHERE catalog_item_id LIKE 'CAT-%')
       AND EXISTS (
           SELECT 1
           FROM (
               SELECT tenant_id, workspace_id, count(*) AS sku_count
               FROM catalog_management.sellable_sku
               WHERE legacy_catalog_item_id LIKE 'CAT-%'
               GROUP BY tenant_id, workspace_id
           ) projection
           WHERE projection.sku_count <> 50
       ) THEN
        RAISE EXCEPTION 'Canonical sellable SKU projection is incomplete after curated mapping';
    END IF;
END $$;

-- Keep redundant seeded family UUIDs resolvable for historical snapshots, but
-- make only the curated families active authorities.
UPDATE catalog_management.product_family f
SET status = 'ARCHIVED',
    updated_at = current_timestamp
WHERE f.family_code LIKE 'FAM-CAT-%'
  AND NOT EXISTS (SELECT 1 FROM catalog_management.sellable_sku s WHERE s.family_id = f.id)
  AND f.status <> 'ARCHIVED';

CREATE INDEX IF NOT EXISTS ix_sellable_sku_canonical_projection
    ON catalog_management.sellable_sku (tenant_id, workspace_id, family_id, status, visible, presentation, id);
