package com.nexa.api.catalogmanagement.infrastructure.persistence;

import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.domain.model.productfamily.ProductFamily;
import com.nexa.api.catalogmanagement.domain.model.sellablesku.SellableSku;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** JDBC adapter for the canonical ProductFamily/SellableSku projections and commands. */
@Profile("!test")
@Repository
public class CatalogSkuService implements CatalogSkuPort {
    private final JdbcTemplate jdbc;
    private final ProductAvailabilityPort availability;
    private final CatalogCommandIdempotencySupport idempotency;

    public CatalogSkuService(JdbcTemplate jdbc, ProductAvailabilityPort availability) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.idempotency = new CatalogCommandIdempotencySupport(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search) {
        int safePage = boundedPage(page);
        int safeSize = boundedSize(size);
        String term = like(search);
        String where = "f.tenant_id=? and f.workspace_id=? and (? is null or lower(f.name) like lower(?) or lower(f.family_code) like lower(?))";
        Object[] arguments = { scope.tenantId(), scope.workspaceId(), term, term, term };
        long total = jdbc.queryForObject("select count(*) from catalog_management.product_family f where " + where,
                Long.class, arguments);
        List<CatalogSkuModels.FamilyView> rows = jdbc.query(familySql(where) + " order by f.name,f.id limit ? offset ?",
                (rs, row) -> family(rs), concat(arguments, safeSize, safePage * safeSize));
        return new CatalogSkuModels.Page<>(rows, safePage, safeSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id) {
        String where = "f.tenant_id=? and f.workspace_id=? and f.id=?";
        return jdbc.query(familySql(where), (rs, row) -> family(rs), scope.tenantId(), scope.workspaceId(), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Product family not found"));
    }

    @Override
    @Transactional
    public CatalogSkuModels.FamilyView insertFamily(CatalogScope scope, ProductFamily family) {
        Instant now = Instant.now();
        jdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,country_of_origin,manufacturer_reference,supplier_reference,storage_family,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                family.id(), scope.tenantId(), scope.workspaceId(), family.code(), family.name(), family.description(),
                family.categoryId(), family.brandId(), family.countryOfOrigin(), family.manufacturerReference(),
                family.supplierReference(), family.storageFamily(), Timestamp.from(now), Timestamp.from(now));
        return family(scope, family.id());
    }

    @Override
    @Transactional
    public CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long expectedVersion) {
        String normalized = normalizedStatus(status);
        int changed = jdbc.update("update catalog_management.product_family set status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, scope.tenantId(), scope.workspaceId(), id, expectedVersion);
        if (changed == 0) throw new IllegalStateException("Product family version is stale or family not found");
        return family(scope, id);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId) {
        return skus(scope, page, size, search, familyId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skusByVariant(CatalogScope scope, int page, int size, String search, UUID variantId) {
        return skus(scope, page, size, search, null, variantId);
    }

    private CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId, UUID variantId) {
        int safePage = boundedPage(page);
        int safeSize = boundedSize(size);
        String term = like(search);
        String familyFilter = familyId == null ? null : familyId.toString();
        String variantFilter = variantId == null ? null : variantId.toString();
        String where = skuWhere();
        Object[] arguments = skuArguments(scope, familyFilter, variantFilter, term);
        long total = jdbc.queryForObject("select count(*) from catalog_management.sellable_sku s" + skuJoins() + " where " + where,
                Long.class, arguments);
        List<CatalogSkuModels.SkuView> rows = jdbc.query(skuSql(where) + " order by f.name,s.presentation,s.id limit ? offset ?",
                (rs, row) -> sku(rs), concat(arguments, safeSize, safePage * safeSize));
        return new CatalogSkuModels.Page<>(enrichAvailability(scope, rows), safePage, safeSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id) {
        String where = "s.tenant_id=? and s.workspace_id=? and s.id=?";
        List<CatalogSkuModels.SkuView> rows = jdbc.query(skuSql(where), (rs, row) -> sku(rs), scope.tenantId(), scope.workspaceId(), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("SKU not found");
        return enrichAvailability(scope, rows).get(0);
    }

    @Override
    @Transactional
    public CatalogSkuModels.SkuView insertSku(CatalogScope scope, SellableSku sku) {
        Instant now = Instant.now();
        jdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,sku_code,gtin,presentation,packaging_type,unit_of_measure,net_weight,gross_weight,pack_quantity,temperature_min,temperature_max,shelf_life_days,minimum_remaining_shelf_life_days,lot_tracking_required,expiry_tracking_required,tax_category,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',true,0,?,?)",
                sku.id(), scope.tenantId(), scope.workspaceId(), sku.familyId(), sku.skuCode(), sku.gtin(), sku.presentation(),
                sku.packagingType(), sku.unitOfMeasure(), sku.netWeight(), sku.grossWeight(), sku.packQuantity(),
                sku.temperatureMin(), sku.temperatureMax(), sku.shelfLifeDays(), sku.minimumRemainingShelfLifeDays(),
                sku.lotTrackingRequired(), sku.expiryTrackingRequired(), sku.taxCategory(), Timestamp.from(now), Timestamp.from(now));
        return sku(scope, sku.id());
    }

    @Override
    @Transactional
    public CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long expectedVersion) {
        String normalized = normalizedStatus(status);
        int changed = jdbc.update("update catalog_management.sellable_sku set status=?,visible=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, "ACTIVE".equals(normalized), scope.tenantId(), scope.workspaceId(), id, expectedVersion);
        if (changed == 0) throw new IllegalStateException("SKU version is stale or SKU not found");
        return sku(scope, id);
    }

    @Override
    @Transactional
    public CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) {
        requireSku(scope, skuId);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "sku-price:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(skuId, amount, currency, validFrom, validUntil, sourceCode, sourceDescription), candidate);
        if (!id.equals(candidate)) {
            return price(scope, skuId, id);
        }
        jdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?,?,0,current_timestamp)",
                id, scope.tenantId(), scope.workspaceId(), skuId, amount, currency, Timestamp.from(validFrom),
                validUntil == null ? null : Timestamp.from(validUntil), optional(sourceCode, 80), optional(sourceDescription, 255));
        return price(scope, skuId, id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId) {
        requireSku(scope, skuId);
        return jdbc.query("select id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,cancelled_at from catalog_management.sku_price where tenant_id=? and workspace_id=? and sku_id=? order by valid_from desc,id",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), skuId);
    }

    private CatalogSkuModels.PriceView price(CatalogScope scope, UUID skuId, UUID priceId) {
        return jdbc.query("select id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,cancelled_at from catalog_management.sku_price where tenant_id=? and workspace_id=? and sku_id=? and id=?",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), skuId, priceId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("SKU price not found"));
    }

    private static CatalogSkuModels.PriceView price(ResultSet rs) throws SQLException {
        return new CatalogSkuModels.PriceView(uuid(rs, "id"), uuid(rs, "sku_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")),
                rs.getString("source_code"), rs.getString("source_description"), rs.getLong("version"),
                rs.getTimestamp("cancelled_at") != null);
    }

    private CatalogSkuModels.FamilyView family(ResultSet rs) throws SQLException {
        return new CatalogSkuModels.FamilyView(uuid(rs, "id"), rs.getString("family_code"), rs.getString("name"),
                rs.getString("description"), uuid(rs, "category_id"), rs.getString("category_name"),
                uuid(rs, "brand_id"), rs.getString("brand_name"), rs.getString("country_of_origin"),
                rs.getString("manufacturer_reference"), rs.getString("supplier_reference"), rs.getString("storage_family"),
                rs.getString("status"), rs.getLong("sku_count"), rs.getString("image_path"),
                rs.getString("image_file_name"), rs.getLong("version"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private CatalogSkuModels.SkuView sku(ResultSet rs) throws SQLException {
        CatalogSkuModels.PriceView currentPrice = rs.getObject("price_id") == null ? null : new CatalogSkuModels.PriceView(
                uuid(rs, "price_id"), uuid(rs, "id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")), rs.getString("source_code"),
                rs.getString("source_description"), rs.getLong("price_version"), rs.getTimestamp("cancelled_at") != null);
        return new CatalogSkuModels.SkuView(uuid(rs, "id"), uuid(rs, "family_id"), rs.getString("family_code"),
                rs.getString("family_name"), rs.getString("category_name"), rs.getString("brand_name"),
                rs.getString("sku_code"), rs.getString("gtin"), rs.getString("presentation"), rs.getString("packaging_type"),
                rs.getString("unit_of_measure"), rs.getBigDecimal("net_weight"), rs.getBigDecimal("gross_weight"),
                rs.getBigDecimal("pack_quantity"), rs.getBigDecimal("temperature_min"), rs.getBigDecimal("temperature_max"),
                rs.getInt("shelf_life_days"), rs.getInt("minimum_remaining_shelf_life_days"), rs.getBoolean("lot_tracking_required"),
                rs.getBoolean("expiry_tracking_required"), rs.getString("tax_category"), rs.getString("status"),
                rs.getBoolean("visible"), rs.getLong("version"), rs.getString("legacy_catalog_item_id"),
                rs.getString("image_path"), rs.getString("image_file_name"), "UNKNOWN", false, Instant.EPOCH,
                currentPrice, instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                uuid(rs, "variant_id"), rs.getString("variant_code"), rs.getString("variant_name"));
    }

    private List<CatalogSkuModels.SkuView> enrichAvailability(CatalogScope scope, List<CatalogSkuModels.SkuView> rows) {
        List<String> ids = rows.stream().map(CatalogSkuModels.SkuView::legacyCatalogItemId).filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (ids.isEmpty()) return rows;
        Map<String, ProductAvailabilityPort.Snapshot> snapshots = availability.find(scope, ids).stream()
                .collect(Collectors.toMap(ProductAvailabilityPort.Snapshot::catalogItemId, Function.identity(), (left, right) -> left));
        return rows.stream().map(row -> {
            ProductAvailabilityPort.Snapshot snapshot = snapshots.get(row.legacyCatalogItemId());
            return snapshot == null ? row : row.withAvailability(snapshot.status(), snapshot.nearExpiry(), snapshot.asOf());
        }).toList();
    }

    private void requireSku(CatalogScope scope, UUID skuId) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), skuId);
        if (count == null || count != 1) throw new IllegalArgumentException("SKU not found");
    }

    private static String familySql(String where) {
        return "select f.id,f.family_code,f.name,f.description,f.category_id,c.name category_name,f.brand_id,b.name brand_name," +
                "f.country_of_origin,f.manufacturer_reference,f.supplier_reference,f.storage_family,f.status,f.version,f.created_at,f.updated_at," +
                "(select count(*) from catalog_management.sellable_sku s0 where s0.tenant_id=f.tenant_id and s0.workspace_id=f.workspace_id and s0.family_id=f.id) sku_count," +
                "image.image_path,image.image_file_name " +
                "from catalog_management.product_family f " +
                "left join catalog_management.category c on c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.id=f.category_id " +
                "left join catalog_management.brand b on b.tenant_id=f.tenant_id and b.workspace_id=f.workspace_id and b.id=f.brand_id " +
                "left join lateral (select a.asset_path image_path,a.file_name image_file_name " +
                "from catalog_management.sellable_sku s1 " +
                "join catalog_management.product_asset_reference a on a.tenant_id=s1.tenant_id and a.workspace_id=s1.workspace_id and a.product_id=s1.legacy_product_id " +
                "where s1.tenant_id=f.tenant_id and s1.workspace_id=f.workspace_id and s1.family_id=f.id " +
                "order by a.sort_order,a.id limit 1) image on true where " + where;
    }

    private static String skuJoins() {
        return " join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id " +
                "left join catalog_management.brand b on b.tenant_id=f.tenant_id and b.workspace_id=f.workspace_id and b.id=f.brand_id " +
                "left join catalog_management.category c on c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.id=f.category_id " +
                "left join catalog_management.product_variant v on v.tenant_id=s.tenant_id and v.workspace_id=s.workspace_id and v.id=s.variant_id";
    }

    private static String skuWhere() {
        return "s.tenant_id=? and s.workspace_id=? and (cast(? as uuid) is null or s.family_id=cast(? as uuid)) and " +
                "(cast(? as uuid) is null or s.variant_id=cast(? as uuid)) and " +
                "(cast(? as text) is null or lower(s.sku_code) like lower(?) or lower(s.presentation) like lower(?) or " +
                "lower(coalesce(s.gtin,'')) like lower(?) or lower(f.name) like lower(?) or " +
                "lower(coalesce(b.name,'')) like lower(?) or lower(coalesce(c.name,'')) like lower(?) or " +
                "lower(coalesce(v.name,'')) like lower(?) or lower(coalesce(v.variant_code,'')) like lower(?))";
    }

    private static String skuSql(String where) {
        return "select s.id,s.family_id,v.id variant_id,v.variant_code,v.name variant_name,f.family_code,f.name family_name,c.name category_name,b.name brand_name," +
                "s.sku_code,s.gtin,s.presentation,s.packaging_type,s.unit_of_measure,s.net_weight,s.gross_weight,s.pack_quantity," +
                "s.temperature_min,s.temperature_max,s.shelf_life_days,s.minimum_remaining_shelf_life_days,s.lot_tracking_required," +
                "s.expiry_tracking_required,s.tax_category,s.status,s.visible,s.version,s.legacy_catalog_item_id," +
                "image.image_path,image.image_file_name,s.created_at,s.updated_at," +
                "p.id price_id,p.amount,p.currency,p.valid_from,p.valid_until,p.source_code,p.source_description,p.version price_version,p.cancelled_at " +
                "from catalog_management.sellable_sku s" + skuJoins() +
                " left join lateral (select a.asset_path image_path,a.file_name image_file_name " +
                "from catalog_management.product_asset_reference a where a.tenant_id=s.tenant_id and a.workspace_id=s.workspace_id " +
                "and a.product_id=s.legacy_product_id order by a.sort_order,a.id limit 1) image on true " +
                "left join lateral (select p0.* from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and " +
                "p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from <= current_timestamp " +
                "and (p0.valid_until is null or p0.valid_until > current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true where " + where;
    }

    private static Object[] skuArguments(CatalogScope scope, String familyFilter, String variantFilter, String term) {
        return new Object[] { scope.tenantId(), scope.workspaceId(), familyFilter, familyFilter,
                variantFilter, variantFilter, term, term, term, term, term, term, term, term, term };
    }

    private static Object[] concat(Object[] values, Object... tail) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length);
        System.arraycopy(tail, 0, result, values.length, tail.length);
        return result;
    }

    private static String uuid(ResultSet rs, String column) throws SQLException {
        UUID value = rs.getObject(column, UUID.class);
        return value == null ? null : value.toString();
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String normalizedStatus(String value) { return value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT); }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid");
        return normalized;
    }
    private static int boundedPage(int value) { return Math.max(0, value); }
    private static int boundedSize(int value) { return Math.max(1, Math.min(100, value)); }
    private static String like(String value) { return value == null || value.isBlank() ? null : "%" + value.trim().replace("%", "\\%").replace("_", "\\_") + "%"; }
}
