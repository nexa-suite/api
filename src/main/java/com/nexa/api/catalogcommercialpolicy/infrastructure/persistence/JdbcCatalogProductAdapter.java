package com.nexa.api.catalogcommercialpolicy.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConflictException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogProductPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogProductAdapter implements CatalogProductPort {
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final CatalogCommandIdempotencySupport idempotency;

    public JdbcCatalogProductAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.idempotency = new CatalogCommandIdempotencySupport(jdbc);
    }

    @Override
    public CatalogManagementModels.Page<CatalogManagementModels.ProductView> products(CatalogScope scope, int page, int size, String search, String status) {
        pageCheck(page, size);
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        StringBuilder where = new StringBuilder(" where p.tenant_id=? and p.workspace_id=?");
        if (search != null && !search.isBlank()) {
            where.append(" and (lower(p.name) like lower(?) or lower(p.product_code) like lower(?) or lower(p.catalog_item_id) like lower(?) or lower(p.slug) like lower(?))");
            String like = "%" + search.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            String normalized = enumValue(status, "status", CatalogItemStatus.values());
            where.append(" and p.status=?");
            args.add(normalized);
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<CatalogManagementModels.ProductView> items = jdbc.query(selectProductSql() + where + " order by p.name,p.id limit ? offset ?",
                (rs, row) -> product(rs), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from catalog_management.product p" + where, Long.class, args.toArray());
        return new CatalogManagementModels.Page<>(items, page, size, total == null ? 0 : total);
    }

    @Override
    public Optional<CatalogManagementModels.ProductView> product(CatalogScope scope, UUID id) {
        return jdbc.query(selectProductSql() + " where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> product(rs), scope.tenantId(), scope.workspaceId(), id).stream().findFirst();
    }

    @Override
    public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode,
            String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature,
            String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath) {
        String normalizedItemId = required(catalogItemId, 64);
        String normalizedCode = required(productCode, 64);
        String normalizedSlug = required(slug, 140);
        String normalizedTemperature = temperature(storageTemperature);
        String normalizedPresentation = required(presentation, 160);
        String normalizedUnit = required(unitOfMeasure == null ? "UNIT" : unitOfMeasure, 32).toUpperCase(Locale.ROOT);
        String normalizedImage = assetPath(imagePath);
        return createProduct(scope, normalizedItemId, normalizedCode, normalizedSlug, name, description, categoryId, brandId,
                normalizedTemperature, normalizedPresentation, normalizedUnit, buyerVisible, normalizedImage, null);
    }

    @Override
    public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode,
            String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature,
            String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, String idempotencyKey) {
        String normalizedItemId = required(catalogItemId, 64);
        String normalizedCode = required(productCode, 64);
        String normalizedSlug = required(slug, 140);
        String normalizedTemperature = temperature(storageTemperature);
        String normalizedPresentation = required(presentation, 160);
        String normalizedUnit = required(unitOfMeasure == null ? "UNIT" : unitOfMeasure, 32).toUpperCase(Locale.ROOT);
        String normalizedImage = assetPath(imagePath);
        String normalizedName = required(name, 200);
        String normalizedDescription = required(description, 4000);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "product:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(normalizedItemId, normalizedCode, normalizedSlug, normalizedName,
                        normalizedDescription, categoryId, brandId, normalizedTemperature, normalizedPresentation, normalizedUnit, buyerVisible, normalizedImage), candidate);
        if (!id.equals(candidate)) return product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product"));
        requireCategory(scope, categoryId);
        requireBrand(scope, brandId);
        Timestamp now = now();
        jdbc.update("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                id, scope.tenantId(), scope.workspaceId(), normalizedItemId, normalizedCode, normalizedSlug,
                normalizedName, normalizedDescription, categoryId, brandId, normalizedTemperature, now, now);
        writePresentation(scope, id, normalizedPresentation, normalizedUnit, now);
        writeVisibility(scope, id, buyerVisible, now);
        writeAsset(scope, id, normalizedImage, normalizedName, now);
        return product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product"));
    }

    @Override
    public CatalogManagementModels.ProductView updateProduct(CatalogScope scope, UUID id, String slug, String name,
            String description, UUID categoryId, UUID brandId, String storageTemperature, String presentation,
            String unitOfMeasure, boolean buyerVisible, String imagePath, long version) {
        requireProduct(scope, id);
        requireCategory(scope, categoryId);
        requireBrand(scope, brandId);
        String normalizedImage = assetPath(imagePath);
        Timestamp now = now();
        int updated = jdbc.update("update catalog_management.product set slug=?,name=?,description=?,category_id=?,brand_id=?,storage_temperature=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                required(slug, 140), required(name, 200), required(description, 4000), categoryId, brandId,
                temperature(storageTemperature), now, scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        String normalizedPresentation = required(presentation, 160);
        String normalizedUnit = required(unitOfMeasure == null ? "UNIT" : unitOfMeasure, 32).toUpperCase(Locale.ROOT);
        writePresentation(scope, id, normalizedPresentation, normalizedUnit, now);
        writeVisibility(scope, id, buyerVisible, now);
        jdbc.update("delete from catalog_management.product_asset_reference where tenant_id=? and workspace_id=? and product_id=?",
                scope.tenantId(), scope.workspaceId(), id);
        writeAsset(scope, id, normalizedImage, name, now);
        return product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product"));
    }

    @Override
    public CatalogManagementModels.ProductView changeStatus(CatalogScope scope, UUID id, String status, long version) {
        String normalized = enumValue(status, "status", CatalogItemStatus.values());
        int updated = jdbc.update("update catalog_management.product set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        return product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product"));
    }

    private String selectProductSql() {
        return "select p.id,p.catalog_item_id,p.product_code,p.slug,p.name,p.description," +
                "p.category_id,c.name category_name,p.brand_id,b.name brand_name,p.storage_temperature,p.status," +
                "pp.presentation,pp.unit_of_measure,coalesce(pv.buyer_visible,false) buyer_visible,asset.asset_path," +
                "current_price.id current_price_id,current_price.amount current_price_amount,current_price.currency current_price_currency," +
                "current_price.valid_from current_price_from,current_price.valid_until current_price_until,current_price.source_code current_price_source_code," +
                "current_price.source_description current_price_source_description,current_price.cancelled current_price_cancelled,current_price.version current_price_version,p.version " +
                "from catalog_management.product p " +
                "join catalog_management.category c on c.tenant_id=p.tenant_id and c.workspace_id=p.workspace_id and c.id=p.category_id " +
                "join catalog_management.brand b on b.tenant_id=p.tenant_id and b.workspace_id=p.workspace_id and b.id=p.brand_id " +
                "left join catalog_management.product_presentation pp on pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id and pp.product_id=p.id " +
                "left join catalog_management.product_visibility pv on pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id and pv.product_id=p.id " +
                "left join lateral (select pr.id,pr.amount,pr.currency,pr.valid_from,pr.valid_until,pr.source_code,pr.source_description,(pr.cancelled_at is not null) cancelled,pr.version " +
                "from catalog_management.product_price pr where pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id and pr.product_id=p.id and pr.cancelled_at is null and pr.valid_from<=current_timestamp and (pr.valid_until is null or pr.valid_until>current_timestamp) " +
                "order by pr.valid_from desc,pr.id desc limit 1) current_price on true " +
                "left join lateral (select pa.asset_path from catalog_management.product_asset_reference pa where pa.tenant_id=p.tenant_id and pa.workspace_id=p.workspace_id and pa.product_id=p.id order by pa.sort_order,pa.id limit 1) asset on true ";
    }

    private CatalogManagementModels.ProductView product(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String imagePath = rs.getString("asset_path");
        return new CatalogManagementModels.ProductView(id.toString(), rs.getString("catalog_item_id"), rs.getString("product_code"),
                rs.getString("slug"), rs.getString("name"), rs.getString("description"), rs.getObject("category_id", UUID.class).toString(),
                rs.getString("category_name"), rs.getObject("brand_id", UUID.class).toString(), rs.getString("brand_name"),
                rs.getString("storage_temperature"), rs.getString("status"), rs.getString("presentation"), rs.getString("unit_of_measure"),
                rs.getBoolean("buyer_visible"), imagePath, currentPrice(rs), rs.getLong("version"));
    }

    private CatalogManagementModels.PriceView currentPrice(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("current_price_id", UUID.class);
        if (id == null) return null;
        return new CatalogManagementModels.PriceView(id.toString(), rs.getObject("id", UUID.class).toString(),
                strip(rs.getBigDecimal("current_price_amount")), strip(rs.getString("current_price_currency")),
                instant(rs.getTimestamp("current_price_from")), instant(rs.getTimestamp("current_price_until")),
                rs.getString("current_price_source_code"), rs.getString("current_price_source_description"),
                rs.getBoolean("current_price_cancelled"), rs.getLong("current_price_version"));
    }

    private void writePresentation(CatalogScope scope, UUID id, String presentation, String unit, Timestamp now) {
        jdbc.update("insert into catalog_management.product_presentation (product_id,tenant_id,workspace_id,presentation,unit_of_measure,version,updated_at) values (?,?,?,?,?,0,?) on conflict (product_id) do update set presentation=excluded.presentation,unit_of_measure=excluded.unit_of_measure,version=catalog_management.product_presentation.version+1,updated_at=excluded.updated_at",
                id, scope.tenantId(), scope.workspaceId(), presentation, unit, now);
    }

    private void writeVisibility(CatalogScope scope, UUID id, boolean buyerVisible, Timestamp now) {
        jdbc.update("insert into catalog_management.product_visibility (product_id,tenant_id,workspace_id,buyer_visible,sales_visible,warehouse_visible,logistics_visible,version,updated_at) values (?,?,?,?,?,?,?,0,?) on conflict (product_id) do update set buyer_visible=excluded.buyer_visible,version=catalog_management.product_visibility.version+1,updated_at=excluded.updated_at",
                id, scope.tenantId(), scope.workspaceId(), buyerVisible, true, true, true, now);
    }

    private void writeAsset(CatalogScope scope, UUID id, String path, String altText, Timestamp now) {
        if (path == null) return;
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        jdbc.update("insert into catalog_management.product_asset_reference (id,tenant_id,workspace_id,product_id,asset_path,file_name,alt_text,sort_order) values (?,?,?,?,?,?,?,0)",
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), id, path, required(fileName, 255), required(altText, 255));
    }

    private void requireProduct(CatalogScope scope, UUID id) {
        if (product(scope, id).isEmpty()) throw new CatalogResourceNotFoundException("product");
    }

    private void requireCategory(CatalogScope scope, UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.category where tenant_id=? and workspace_id=? and id=? and status<>'ARCHIVED'",
                Integer.class, scope.tenantId(), scope.workspaceId(), id);
        if (count == null || count != 1) throw new CatalogResourceNotFoundException("category");
    }

    private void requireBrand(CatalogScope scope, UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.brand where tenant_id=? and workspace_id=? and id=? and status<>'ARCHIVED'",
                Integer.class, scope.tenantId(), scope.workspaceId(), id);
        if (count == null || count != 1) throw new CatalogResourceNotFoundException("brand");
    }

    private static String temperature(String value) {
        return enumValue(value, "storageTemperature", new String[]{"AMBIENT", "REFRIGERATED", "FROZEN"});
    }

    private static String enumValue(String value, String field, CatalogItemStatus[] values) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (CatalogItemStatus candidate : values) if (candidate.name().equals(normalized)) return normalized;
        throw new IllegalArgumentException(field + " is invalid");
    }

    private static String enumValue(String value, String field, String[] values) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (String candidate : values) if (candidate.equals(normalized)) return normalized;
        throw new IllegalArgumentException(field + " is invalid");
    }

    private static String required(String value, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid");
        return normalized;
    }

    private static String assetPath(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (!normalized.startsWith("/catalog-items/") || normalized.contains("..") || normalized.length() > 512) {
            throw new IllegalArgumentException("Catalog asset path is invalid");
        }
        return normalized;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static BigDecimal strip(BigDecimal value) { return value == null ? null : value.stripTrailingZeros(); }
    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static void pageCheck(int page, int size) { if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid catalog pagination"); }
}
