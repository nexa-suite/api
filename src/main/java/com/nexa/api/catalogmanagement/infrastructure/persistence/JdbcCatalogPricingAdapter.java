package com.nexa.api.catalogmanagement.infrastructure.persistence;

import com.nexa.api.catalogmanagement.application.exception.CatalogConcurrencyException;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;
import com.nexa.api.catalogmanagement.domain.model.pricing.PricePeriod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogPricingAdapter implements CatalogPricingPort {
    private final JdbcTemplate jdbc;
    private final CatalogCommandIdempotencySupport idempotency;

    public JdbcCatalogPricingAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.idempotency = new CatalogCommandIdempotencySupport(jdbc);
    }

    @Override
    public List<CatalogManagementModels.PriceView> history(CatalogScope scope, UUID productId) {
        requireProduct(scope, productId);
        return canonicalSku(scope, productId).map(skuId -> jdbc.query("select p.id,s.legacy_product_id product_id,p.amount,p.currency,p.valid_from,p.valid_until,p.source_code,p.source_description,p.cancelled_at,p.version from catalog_management.sku_price p join catalog_management.sellable_sku s on s.tenant_id=p.tenant_id and s.workspace_id=p.workspace_id and s.id=p.sku_id where p.tenant_id=? and p.workspace_id=? and p.sku_id=? order by p.valid_from desc,p.id desc",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), skuId)).orElseGet(() -> jdbc.query("select id,product_id,amount,currency,valid_from,valid_until,source_code,source_description,cancelled_at,version from catalog_management.product_price where tenant_id=? and workspace_id=? and product_id=? order by valid_from desc,id desc",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), productId));
    }

    @Override
    public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) {
        return create(scope, productId, amount, currency, validFrom, validUntil, sourceCode, sourceDescription, null);
    }

    @Override
    public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) {
        requireProduct(scope, productId);
        UUID skuId = ensureCanonicalSku(scope, productId);
        String normalizedCurrency = normalizedCurrency(scope, currency);
        PricePeriod period = new PricePeriod(validFrom == null ? Instant.now() : validFrom, validUntil);
        Money money = Money.from(amount, normalizedCurrency);
        String normalizedSourceCode = optional(sourceCode, 80);
        String normalizedSourceDescription = optional(sourceDescription, 255);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "price:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(productId, money.amount(), normalizedCurrency, period.validFrom(), period.validUntil(), normalizedSourceCode, normalizedSourceDescription), candidate);
        if (!id.equals(candidate)) {
            return jdbc.query("select id,product_id,amount,currency,valid_from,valid_until,source_code,source_description,cancelled_at,version from catalog_management.product_price where tenant_id=? and workspace_id=? and id=?",
                    (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), id).stream().findFirst()
                    .orElseThrow(() -> new CatalogResourceNotFoundException("price"));
        }
        jdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?, ?,0,?)",
                id, scope.tenantId(), scope.workspaceId(), skuId, money.amount(), normalizedCurrency,
                timestamp(period.validFrom()), timestamp(period.validUntil()), normalizedSourceCode, normalizedSourceDescription, timestamp(Instant.now()));
        // Compatibility projection: old readers remain resolvable, but canonical
        // price authority is sku_price above.
        jdbc.update("insert into catalog_management.product_price (id,tenant_id,workspace_id,product_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?, ?,0,?)",
                id, scope.tenantId(), scope.workspaceId(), productId, money.amount(), normalizedCurrency,
                timestamp(period.validFrom()), timestamp(period.validUntil()), normalizedSourceCode, normalizedSourceDescription, timestamp(Instant.now()));
        return jdbc.query("select id,product_id,amount,currency,valid_from,valid_until,source_code,source_description,cancelled_at,version from catalog_management.product_price where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), id).stream().findFirst()
                .orElseThrow(() -> new CatalogResourceNotFoundException("price"));
    }

    @Override
    public CatalogManagementModels.PriceView cancel(CatalogScope scope, UUID priceId, long version) {
        if (!exists(scope, priceId)) throw new CatalogResourceNotFoundException("price");
        int updated = jdbc.update("update catalog_management.sku_price set cancelled_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and cancelled_at is null",
                scope.tenantId(), scope.workspaceId(), priceId, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        jdbc.update("update catalog_management.product_price set cancelled_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and cancelled_at is null",
                scope.tenantId(), scope.workspaceId(), priceId);
        return jdbc.query("select id,product_id,amount,currency,valid_from,valid_until,source_code,source_description,cancelled_at,version from catalog_management.product_price where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> price(rs), scope.tenantId(), scope.workspaceId(), priceId).stream().findFirst()
                .orElseThrow(() -> new CatalogResourceNotFoundException("price"));
    }

    private CatalogManagementModels.PriceView price(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        return new CatalogManagementModels.PriceView(rs.getObject("id", UUID.class).toString(), rs.getObject("product_id", UUID.class).toString(),
                amount == null ? null : amount.stripTrailingZeros(), rs.getString("currency").strip(), instant(rs.getTimestamp("valid_from")),
                instant(rs.getTimestamp("valid_until")), rs.getString("source_code"), rs.getString("source_description"),
                rs.getTimestamp("cancelled_at") != null, rs.getLong("version"));
    }

    private void requireProduct(CatalogScope scope, UUID productId) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.product where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), productId);
        if (count == null || count != 1) throw new CatalogResourceNotFoundException("product");
    }

    private boolean exists(CatalogScope scope, UUID priceId) {
        Integer count = jdbc.queryForObject("select count(*) from catalog_management.sku_price where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), priceId);
        return count != null && count == 1;
    }

    private UUID ensureCanonicalSku(CatalogScope scope, UUID productId) {
        Optional<UUID> canonical = canonicalSku(scope, productId);
        if (canonical.isPresent()) return canonical.get();
        ProductSeed seed = jdbc.query("select p.catalog_item_id,p.product_code,p.name,p.description,p.category_id,p.brand_id,p.storage_temperature,p.status,p.version,p.created_at,p.updated_at,coalesce(pp.presentation,p.name),coalesce(pp.unit_of_measure,'UNIT') from catalog_management.product p left join catalog_management.product_presentation pp on pp.product_id=p.id and pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> new ProductSeed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getTimestamp(10), rs.getTimestamp(11), rs.getString(12), rs.getString(13)), scope.tenantId(), scope.workspaceId(), productId)
                .stream().findFirst().orElseThrow(() -> new CatalogResourceNotFoundException("product"));
        UUID familyId = UUID.randomUUID();
        jdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,family_code) do nothing",
                familyId, scope.tenantId(), scope.workspaceId(), "FAM-" + seed.catalogItemId(), seed.name(), seed.description(), seed.categoryId(), seed.brandId(), seed.storageTemperature(), familyStatus(seed.status()), seed.createdAt(), seed.updatedAt());
        familyId = jdbc.queryForObject("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?", UUID.class,
                scope.tenantId(), scope.workspaceId(), "FAM-" + seed.catalogItemId());
        jdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,legacy_product_id,legacy_catalog_item_id,sku_code,presentation,packaging_type,unit_of_measure,pack_quantity,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,legacy_catalog_item_id) do nothing",
                productId, scope.tenantId(), scope.workspaceId(), familyId, productId, seed.catalogItemId(), seed.productCode(), seed.presentation(), "UNSPECIFIED", seed.unit(), BigDecimal.ONE, seed.status(), true, seed.version(), seed.createdAt(), seed.updatedAt());
        return jdbc.queryForObject("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and legacy_catalog_item_id=?", UUID.class,
                scope.tenantId(), scope.workspaceId(), seed.catalogItemId());
    }

    private static String familyStatus(String status) { return "DISCONTINUED".equals(status) ? "ARCHIVED" : status; }
    private Optional<UUID> canonicalSku(CatalogScope scope, UUID productId) {
        return jdbc.query("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and (id=? or legacy_product_id=?)",
                (rs, row) -> rs.getObject(1, UUID.class), scope.tenantId(), scope.workspaceId(), productId, productId).stream().findFirst();
    }

    private String normalizedCurrency(CatalogScope scope, String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        Money.from(BigDecimal.ZERO, normalized);
        String configured = jdbc.query("select currency from tenant_management.regional_settings where tenant_id=?",
                (rs, row) -> rs.getString(1), scope.tenantId()).stream().findFirst().orElse(null);
        if (configured != null && !normalized.equals(configured.strip().toUpperCase(Locale.ROOT))) {
            throw new com.nexa.api.catalogmanagement.application.exception.CatalogConflictException("CATALOG_CURRENCY_MISMATCH");
        }
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid");
        return normalized;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record ProductSeed(String catalogItemId, String productCode, String name, String description, UUID categoryId,
            UUID brandId, String storageTemperature, String status, long version, Timestamp createdAt, Timestamp updatedAt,
            String presentation, String unit) { }
}
