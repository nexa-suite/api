package com.nexa.api.catalogmanagement.infrastructure.persistence;

import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.domain.model.productfamily.ProductFamily;
import com.nexa.api.catalogmanagement.domain.model.sellablesku.SellableSku;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence adapter for Product Family and Sellable SKU resources. */
@Profile("!test")
@Repository
public class CatalogSkuService implements CatalogSkuPort {
    private final JdbcTemplate jdbc;
    private final CatalogAuthorizationPort authorization;

    public CatalogSkuService(JdbcTemplate jdbc, CatalogAuthorizationPort authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search) {
        read();
        int safePage = boundedPage(page);
        int safeSize = boundedSize(size);
        String term = like(search);
        String where = "tenant_id=? and workspace_id=? and (? is null or lower(name) like lower(?) or lower(family_code) like lower(?))";
        long total = jdbc.queryForObject("select count(*) from catalog_management.product_family where " + where,
                Long.class, tenant(scope), workspace(scope), term, term, term);
        List<CatalogSkuModels.FamilyView> rows = jdbc.query("select id,family_code,name,description,category_id,brand_id,country_of_origin,manufacturer_reference,supplier_reference,storage_family,status,version,created_at,updated_at from catalog_management.product_family where " + where + " order by name,id limit ? offset ?",
                (rs, row) -> new CatalogSkuModels.FamilyView(rs.getObject("id", UUID.class).toString(), rs.getString("family_code"), rs.getString("name"),
                        rs.getString("description"), rs.getObject("category_id", UUID.class).toString(), rs.getObject("brand_id", UUID.class).toString(),
                        rs.getString("country_of_origin"), rs.getString("manufacturer_reference"), rs.getString("supplier_reference"),
                        rs.getString("storage_family"), rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenant(scope), workspace(scope), term, term, term, safeSize, safePage * safeSize);
        return new CatalogSkuModels.Page<>(rows, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id) {
        read();
        return jdbc.query("select id,family_code,name,description,category_id,brand_id,country_of_origin,manufacturer_reference,supplier_reference,storage_family,status,version,created_at,updated_at from catalog_management.product_family where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new CatalogSkuModels.FamilyView(rs.getObject("id", UUID.class).toString(), rs.getString("family_code"), rs.getString("name"),
                        rs.getString("description"), rs.getObject("category_id", UUID.class).toString(), rs.getObject("brand_id", UUID.class).toString(),
                        rs.getString("country_of_origin"), rs.getString("manufacturer_reference"), rs.getString("supplier_reference"),
                        rs.getString("storage_family"), rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenant(scope), workspace(scope), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Product family not found"));
    }

    @Transactional
    public CatalogSkuModels.FamilyView createFamily(CatalogScope scope, String code, String name, String description,
            UUID categoryId, UUID brandId, String country, String manufacturer, String supplier, String storageFamily) {
        write(CatalogPermissions.MANAGE);
        Instant now = Instant.now();
        ProductFamily family = ProductFamily.create(tenant(scope), workspace(scope), code, name, description, categoryId, brandId,
                country, manufacturer, supplier, storageFamily, now);
        jdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,country_of_origin,manufacturer_reference,supplier_reference,storage_family,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                family.id(), family.tenantId(), family.workspaceId(), family.code(), family.name(), family.description(), family.categoryId(), family.brandId(),
                family.countryOfOrigin(), family.manufacturerReference(), family.supplierReference(), family.storageFamily(), Timestamp.from(now), Timestamp.from(now));
        return family(scope, family.id());
    }

    @Transactional
    public CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long expectedVersion) {
        write(CatalogPermissions.MANAGE);
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVE", "INACTIVE", "ARCHIVED").contains(normalized)) throw new IllegalArgumentException("Family status is invalid");
        int changed = jdbc.update("update catalog_management.product_family set status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, tenant(scope), workspace(scope), id, expectedVersion);
        if (changed == 0) throw new IllegalStateException("Product family version is stale or family not found");
        return family(scope, id);
    }

    @Transactional(readOnly = true)
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId) {
        read();
        int safePage = boundedPage(page);
        int safeSize = boundedSize(size);
        String term = like(search);
        String familyFilter = familyId == null ? null : familyId.toString();
        String where = "s.tenant_id=? and s.workspace_id=? and (? is null or s.family_id=cast(? as uuid)) and (? is null or lower(s.sku_code) like lower(?) or lower(s.presentation) like lower(?) or lower(coalesce(s.gtin,'')) like lower(?) or lower(f.name) like lower(?) or lower(coalesce(b.name,'')) like lower(?) or lower(coalesce(c.name,'')) like lower(?))";
        String joins = " join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                + "left join catalog_management.brand b on b.tenant_id=f.tenant_id and b.workspace_id=f.workspace_id and b.id=f.brand_id "
                + "left join catalog_management.category c on c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.id=f.category_id ";
        long total = jdbc.queryForObject("select count(*) from catalog_management.sellable_sku s" + joins + "where " + where,
                Long.class, tenant(scope), workspace(scope), familyFilter, familyFilter, term, term, term, term, term, term, term);
        List<CatalogSkuModels.SkuView> rows = jdbc.query("select s.id,s.family_id,f.name family_name,s.sku_code,s.gtin,s.presentation,s.packaging_type,s.unit_of_measure,s.net_weight,s.gross_weight,s.pack_quantity,s.temperature_min,s.temperature_max,s.shelf_life_days,s.minimum_remaining_shelf_life_days,s.lot_tracking_required,s.expiry_tracking_required,s.tax_category,s.status,s.visible,s.version,s.created_at,s.updated_at,p.id price_id,p.amount,p.currency,p.valid_from,p.valid_until,p.source_code,p.source_description,p.version price_version,p.cancelled_at from catalog_management.sellable_sku s" + joins + "left join lateral (select * from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from <= current_timestamp and (p0.valid_until is null or p0.valid_until > current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true where " + where + " order by f.name,s.presentation,s.id limit ? offset ?",
                (rs, row) -> sku(rs), tenant(scope), workspace(scope), familyFilter, familyFilter, term, term, term, term, term, term, term, safeSize, safePage * safeSize);
        return new CatalogSkuModels.Page<>(rows, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id) {
        read();
        return jdbc.query("select s.id,s.family_id,f.name family_name,s.sku_code,s.gtin,s.presentation,s.packaging_type,s.unit_of_measure,s.net_weight,s.gross_weight,s.pack_quantity,s.temperature_min,s.temperature_max,s.shelf_life_days,s.minimum_remaining_shelf_life_days,s.lot_tracking_required,s.expiry_tracking_required,s.tax_category,s.status,s.visible,s.version,s.created_at,s.updated_at,p.id price_id,p.amount,p.currency,p.valid_from,p.valid_until,p.source_code,p.source_description,p.version price_version,p.cancelled_at from catalog_management.sellable_sku s join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id left join lateral (select * from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from <= current_timestamp and (p0.valid_until is null or p0.valid_until > current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true where s.tenant_id=? and s.workspace_id=? and s.id=?",
                (rs, row) -> sku(rs), tenant(scope), workspace(scope), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("SKU not found"));
    }

    @Transactional
    public CatalogSkuModels.SkuView createSku(CatalogScope scope, UUID familyId, String skuCode, String gtin,
            String presentation, String packaging, String unit, BigDecimal netWeight, BigDecimal grossWeight,
            BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays,
            int minimumRemainingShelfLifeDays, boolean lotTracking, boolean expiryTracking, String taxCategory) {
        write(CatalogPermissions.MANAGE);
        family(scope, familyId);
        Instant now = Instant.now();
        SellableSku sku = SellableSku.create(tenant(scope), workspace(scope), familyId, skuCode, gtin, presentation,
                packaging, unit, netWeight, grossWeight, packQuantity == null ? BigDecimal.ONE : packQuantity,
                temperatureMin, temperatureMax, shelfLifeDays, minimumRemainingShelfLifeDays, lotTracking, expiryTracking,
                taxCategory == null ? "STANDARD" : taxCategory, now);
        jdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,sku_code,gtin,presentation,packaging_type,unit_of_measure,net_weight,gross_weight,pack_quantity,temperature_min,temperature_max,shelf_life_days,minimum_remaining_shelf_life_days,lot_tracking_required,expiry_tracking_required,tax_category,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',true,0,?,?)",
                sku.id(), sku.tenantId(), sku.workspaceId(), sku.familyId(), sku.skuCode(), sku.gtin(), sku.presentation(), sku.packagingType(), sku.unitOfMeasure(),
                sku.netWeight(), sku.grossWeight(), sku.packQuantity(), sku.temperatureMin(), sku.temperatureMax(), sku.shelfLifeDays(), sku.minimumRemainingShelfLifeDays(),
                sku.lotTrackingRequired(), sku.expiryTrackingRequired(), sku.taxCategory(), Timestamp.from(now), Timestamp.from(now));
        return sku(scope, sku.id());
    }

    @Transactional
    public CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long expectedVersion) {
        write(CatalogPermissions.MANAGE);
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVE", "INACTIVE", "DISCONTINUED", "ARCHIVED").contains(normalized)) throw new IllegalArgumentException("SKU status is invalid");
        int changed = jdbc.update("update catalog_management.sellable_sku set status=?,visible=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, "ACTIVE".equals(normalized), tenant(scope), workspace(scope), id, expectedVersion);
        if (changed == 0) throw new IllegalStateException("SKU version is stale or SKU not found");
        return sku(scope, id);
    }

    @Transactional
    public CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) {
        write(CatalogPermissions.PRICE_MANAGE);
        sku(scope, skuId);
        if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Price amount is invalid");
        String normalizedCurrency = currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalizedCurrency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency is invalid");
        Instant start = validFrom == null ? Instant.now() : validFrom;
        if (validUntil != null && !validUntil.isAfter(start)) throw new IllegalArgumentException("Price validity is invalid");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?,?,0,current_timestamp)",
                id, tenant(scope), workspace(scope), skuId, amount, normalizedCurrency, Timestamp.from(start), validUntil == null ? null : Timestamp.from(validUntil), sourceCode, sourceDescription);
        return prices(scope, skuId).stream().filter(value -> value.id().equals(id.toString())).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId) {
        read();
        sku(scope, skuId);
        return jdbc.query("select id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,cancelled_at from catalog_management.sku_price where tenant_id=? and workspace_id=? and sku_id=? order by valid_from desc,id",
                (rs, row) -> new CatalogSkuModels.PriceView(rs.getObject("id", UUID.class).toString(), rs.getObject("sku_id", UUID.class).toString(), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant(), rs.getString("source_code"), rs.getString("source_description"), rs.getLong("version"), rs.getTimestamp("cancelled_at") != null), tenant(scope), workspace(scope), skuId);
    }

    private CatalogSkuModels.SkuView sku(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp validFrom = rs.getTimestamp("valid_from");
        CatalogSkuModels.PriceView price = rs.getObject("price_id") == null ? null : new CatalogSkuModels.PriceView(rs.getObject("price_id", UUID.class).toString(), rs.getObject("id", UUID.class).toString(), rs.getBigDecimal("amount"), rs.getString("currency"), validFrom.toInstant(), rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant(), rs.getString("source_code"), rs.getString("source_description"), rs.getLong("price_version"), rs.getTimestamp("cancelled_at") != null);
        return new CatalogSkuModels.SkuView(rs.getObject("id", UUID.class).toString(), rs.getObject("family_id", UUID.class).toString(), rs.getString("family_name"), rs.getString("sku_code"), rs.getString("gtin"), rs.getString("presentation"), rs.getString("packaging_type"), rs.getString("unit_of_measure"), rs.getBigDecimal("net_weight"), rs.getBigDecimal("gross_weight"), rs.getBigDecimal("pack_quantity"), rs.getBigDecimal("temperature_min"), rs.getBigDecimal("temperature_max"), rs.getInt("shelf_life_days"), rs.getInt("minimum_remaining_shelf_life_days"), rs.getBoolean("lot_tracking_required"), rs.getBoolean("expiry_tracking_required"), rs.getString("tax_category"), rs.getString("status"), rs.getBoolean("visible"), rs.getLong("version"), price, rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private void read() { authorization.require(CatalogPermissions.READ); }
    private void write(String permission) { authorization.require(permission); }
    private static UUID tenant(CatalogScope scope) { return scope.tenantId(); }
    private static UUID workspace(CatalogScope scope) { return scope.workspaceId(); }
    private static int boundedPage(int value) { return Math.max(0, value); }
    private static int boundedSize(int value) { return Math.max(1, Math.min(100, value)); }
    private static String like(String value) { return value == null || value.isBlank() ? null : "%" + value.trim().replace("%", "\\%").replace("_", "\\_") + "%"; }
}
