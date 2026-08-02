package com.nexa.api.catalogmanagement.infrastructure.persistence;

import com.nexa.api.catalogmanagement.application.exception.CatalogConcurrencyException;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogmanagement.application.exception.CatalogConflictException;
import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogPromotionAdapter implements CatalogPromotionPort {
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final CatalogCommandIdempotencySupport idempotency;

    public JdbcCatalogPromotionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.idempotency = new CatalogCommandIdempotencySupport(jdbc);
    }

    @Override
    public CatalogManagementModels.Page<CatalogManagementModels.PromotionView> promotions(CatalogScope scope, int page, int size, String status) {
        pageCheck(page, size);
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        StringBuilder where = new StringBuilder(" where tenant_id=? and workspace_id=?");
        if (status != null && !status.isBlank()) {
            String normalized = enumValue(status, "status", PromotionStatus.values());
            where.append(" and status=?");
            args.add(normalized);
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<UUID> ids = jdbc.query("select id from catalog_management.promotion" + where + " order by starts_at nulls last,name,id limit ? offset ?",
                (rs, row) -> rs.getObject(1, UUID.class), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from catalog_management.promotion" + where, Long.class, args.toArray());
        List<CatalogManagementModels.PromotionView> items = ids.stream().map(id -> promotion(scope, id)).toList();
        return new CatalogManagementModels.Page<>(items, page, size, total == null ? 0 : total);
    }

    @Override
    @Transactional
    public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, null);
    }

    @Override
    @Transactional
    public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey) {
        List<UUID> products = distinct(productIds);
        List<UUID> categories = distinct(categoryIds);
        PromotionValues values = values(discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
        UUID candidate = UUID.randomUUID();
        UUID id = idempotency.reserve(scope, "promotion:create", idempotencyKey,
                CatalogCommandIdempotencySupport.hash(slug, name, description, values.discountType(), values.discountValue(), values.currency(), startsAt, endsAt, values.minimumQuantity(), values.stackingPolicy(), products, categories), candidate);
        if (!id.equals(candidate)) return promotion(scope, id);
        products.forEach(value -> requireProduct(scope, value));
        categories.forEach(value -> requireCategory(scope, value));
        Timestamp now = now();
        jdbc.update("insert into catalog_management.promotion (id,tenant_id,workspace_id,slug,name,description,status,discount_type,discount_value,currency,starts_at,ends_at,minimum_quantity,stacking_policy,version,created_at,updated_at) values (?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,0,?,?)",
                id, scope.tenantId(), scope.workspaceId(), required(slug, 140), required(name, 200), optional(description, 2000),
                values.discountType(), values.discountValue(), values.currency(), timestamp(startsAt), timestamp(endsAt),
                values.minimumQuantity(), values.stackingPolicy(), now, now);
        writeTargets(scope, id, products, categories);
        return promotion(scope, id);
    }

    @Override
    @Transactional
    public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version) {
        requirePromotion(scope, id);
        List<UUID> products = distinct(productIds);
        List<UUID> categories = distinct(categoryIds);
        products.forEach(value -> requireProduct(scope, value));
        categories.forEach(value -> requireCategory(scope, value));
        PromotionValues values = values(discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
        int updated = jdbc.update("update catalog_management.promotion set slug=?,name=?,description=?,discount_type=?,discount_value=?,currency=?,starts_at=?,ends_at=?,minimum_quantity=?,stacking_policy=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                required(slug, 140), required(name, 200), optional(description, 2000), values.discountType(), values.discountValue(),
                values.currency(), timestamp(startsAt), timestamp(endsAt), values.minimumQuantity(), values.stackingPolicy(), now(),
                scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        writeTargets(scope, id, products, categories);
        return promotion(scope, id);
    }

    @Override
    public CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version) {
        CatalogManagementModels.PromotionView current = loadPromotion(scope, id);
        String normalized = enumValue(status, "status", PromotionStatus.values());
        if (!validTransition(current.status(), normalized)) throw new CatalogConflictException("PROMOTION_LIFECYCLE_INVALID");
        int updated = jdbc.update("update catalog_management.promotion set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalized, now(), scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        return promotion(scope, id);
    }

    @Override
    public CatalogManagementModels.PromotionView promotion(CatalogScope scope, UUID id) {
        return loadPromotion(scope, id);
    }

    private CatalogManagementModels.PromotionView loadPromotion(CatalogScope scope, UUID id) {
        List<CatalogManagementModels.PromotionView> result = jdbc.query("select id,slug,name,description,status,discount_type,discount_value,currency,starts_at,ends_at,minimum_quantity,stacking_policy,version from catalog_management.promotion where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> view(scope, rs), scope.tenantId(), scope.workspaceId(), id);
        if (result.isEmpty()) throw new CatalogResourceNotFoundException("promotion");
        return result.getFirst();
    }

    private static boolean validTransition(String current, String target) {
        if (current.equals(target)) return true;
        return switch (current) {
            case "DRAFT" -> target.equals("SCHEDULED") || target.equals("ACTIVE") || target.equals("CANCELLED");
            case "SCHEDULED" -> target.equals("ACTIVE") || target.equals("CANCELLED") || target.equals("EXPIRED");
            case "ACTIVE" -> target.equals("PAUSED") || target.equals("CANCELLED") || target.equals("EXPIRED");
            case "PAUSED" -> target.equals("ACTIVE") || target.equals("CANCELLED") || target.equals("EXPIRED");
            case "EXPIRED", "CANCELLED" -> false;
            default -> false;
        };
    }

    private CatalogManagementModels.PromotionView view(CatalogScope scope, ResultSet rs) throws SQLException {
        UUID promotionId = rs.getObject("id", UUID.class);
        List<String> products = jdbc.query("select product_id from catalog_management.promotion_product where tenant_id=? and workspace_id=? and promotion_id=? order by product_id",
                (mapping, row) -> mapping.getObject(1, UUID.class).toString(), scope.tenantId(), scope.workspaceId(), promotionId);
        List<String> categories = jdbc.query("select category_id from catalog_management.promotion_category where tenant_id=? and workspace_id=? and promotion_id=? order by category_id",
                (mapping, row) -> mapping.getObject(1, UUID.class).toString(), scope.tenantId(), scope.workspaceId(), promotionId);
        return new CatalogManagementModels.PromotionView(promotionId.toString(), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getString("discount_type"), rs.getBigDecimal("discount_value"), strip(rs.getString("currency")),
                instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")), rs.getBigDecimal("minimum_quantity"),
                rs.getString("stacking_policy"), products, categories, rs.getLong("version"));
    }

    private void writeTargets(CatalogScope scope, UUID promotionId, List<UUID> products, List<UUID> categories) {
        jdbc.update("delete from catalog_management.promotion_product where tenant_id=? and workspace_id=? and promotion_id=?",
                scope.tenantId(), scope.workspaceId(), promotionId);
        jdbc.update("delete from catalog_management.promotion_category where tenant_id=? and workspace_id=? and promotion_id=?",
                scope.tenantId(), scope.workspaceId(), promotionId);
        for (UUID productId : products) {
            jdbc.update("insert into catalog_management.promotion_product (promotion_id,tenant_id,workspace_id,product_id) values (?,?,?,?)",
                    promotionId, scope.tenantId(), scope.workspaceId(), productId);
        }
        for (UUID categoryId : categories) {
            jdbc.update("insert into catalog_management.promotion_category (promotion_id,tenant_id,workspace_id,category_id) values (?,?,?,?)",
                    promotionId, scope.tenantId(), scope.workspaceId(), categoryId);
        }
    }

    private void requirePromotion(CatalogScope scope, UUID id) {
        if (jdbc.queryForObject("select count(*) from catalog_management.promotion where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), id) != 1) throw new CatalogResourceNotFoundException("promotion");
    }

    private void requireProduct(CatalogScope scope, UUID id) {
        if (jdbc.queryForObject("select count(*) from catalog_management.product where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), id) != 1) throw new CatalogResourceNotFoundException("product");
    }

    private void requireCategory(CatalogScope scope, UUID id) {
        if (jdbc.queryForObject("select count(*) from catalog_management.category where tenant_id=? and workspace_id=? and id=?",
                Integer.class, scope.tenantId(), scope.workspaceId(), id) != 1) throw new CatalogResourceNotFoundException("category");
    }

    private static PromotionValues values(String discountType, BigDecimal discountValue, String currency, Instant startsAt,
            Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy) {
        String type = enumValue(discountType, "discountType", "PERCENTAGE", "FIXED_AMOUNT");
        if (discountValue == null || discountValue.signum() < 0 || "PERCENTAGE".equals(type) && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Promotion discount is invalid");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Promotion period is invalid");
        BigDecimal quantity = minimumQuantity == null ? BigDecimal.ONE : minimumQuantity;
        if (quantity.signum() <= 0) throw new IllegalArgumentException("Promotion quantity is invalid");
        String normalizedCurrency = "PERCENTAGE".equals(type) ? null : currency(currency);
        if ("PERCENTAGE".equals(type) && currency != null && !currency.isBlank()) throw new IllegalArgumentException("Percentage promotion cannot define currency");
        return new PromotionValues(type, discountValue, normalizedCurrency, quantity, enumValue(stackingPolicy == null ? "EXCLUSIVE" : stackingPolicy, "stackingPolicy", "EXCLUSIVE", "STACKABLE"));
    }

    private static List<UUID> distinct(List<UUID> values) {
        if (values == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String currency(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        Money.from(BigDecimal.ZERO, normalized);
        return normalized;
    }

    private static String enumValue(String value, String field, PromotionStatus[] allowed) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (PromotionStatus candidate : allowed) if (candidate.name().equals(normalized)) return normalized;
        throw new IllegalArgumentException(field + " is invalid");
    }

    private static String enumValue(String value, String field, String... allowed) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return normalized;
        throw new IllegalArgumentException(field + " is invalid");
    }

    private static String required(String value, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException("Catalog value is invalid");
        return normalized;
    }

    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static void pageCheck(int page, int size) { if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid catalog pagination"); }

    private record PromotionValues(String discountType, BigDecimal discountValue, String currency, BigDecimal minimumQuantity, String stackingPolicy) { }
}
