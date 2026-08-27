package com.nexa.api.catalogcommercialpolicy.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConcurrencyException;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.Money;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
			List<PromotionRow> rows = jdbc.query("select id,slug,name,description,status,discount_type,discount_value,currency,starts_at,ends_at,minimum_quantity,stacking_policy,priority,version from catalog_management.promotion" + where + " order by starts_at nulls last,name,id limit ? offset ?",
				(rs, row) -> new PromotionRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
						rs.getString("description"), rs.getString("status"), rs.getString("discount_type"), rs.getBigDecimal("discount_value"),
						strip(rs.getString("currency")), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
						 rs.getBigDecimal("minimum_quantity"), rs.getString("stacking_policy"), rs.getInt("priority"), rs.getLong("version")), pageArgs.toArray());
		Long total = jdbc.queryForObject("select count(*) from catalog_management.promotion" + where, Long.class, args.toArray());
		List<UUID> ids = rows.stream().map(PromotionRow::id).toList();
		Map<UUID, List<String>> products = targetStrings(scope, "promotion_product", "product_id", ids);
		Map<UUID, List<String>> categories = targetStrings(scope, "promotion_category", "category_id", ids);
		Map<UUID, List<String>> clients = targetStrings(scope, "promotion_client_account", "client_account_id", ids);
		Map<UUID, List<CatalogManagementModels.PromotionRuleView>> rules = targetRules(scope, ids);
		List<CatalogManagementModels.PromotionView> items = rows.stream().map(row -> new CatalogManagementModels.PromotionView(
				row.id().toString(), row.slug(), row.name(), row.description(), row.status(), row.discountType(), row.discountValue(),
				row.currency(), row.startsAt(), row.endsAt(), row.minimumQuantity(), row.stackingPolicy(),
				row.priority(), products.getOrDefault(row.id(), List.of()), categories.getOrDefault(row.id(), List.of()),
				clients.getOrDefault(row.id(), List.of()), rules.getOrDefault(row.id(), List.of()), row.version())).toList();
		return new CatalogManagementModels.Page<>(items, page, size, total == null ? 0 : total);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), null);
    }

    @Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), idempotencyKey);
    }

    @Override
    public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey) {
		return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, idempotencyKey, 0);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey, int priority) {
        List<UUID> products = distinct(productIds);
        List<UUID> categories = distinct(categoryIds);
        List<UUID> clients = distinct(clientAccountIds);
        List<CatalogManagementModels.PromotionRuleView> normalizedRules = rules == null ? List.of() : List.copyOf(rules);
        PromotionValues values = values(discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
        UUID candidate = UUID.randomUUID();
		if (priority < -1_000_000 || priority > 1_000_000) throw new IllegalArgumentException("Promotion priority is invalid");
		UUID id = idempotency.reserve(scope, "promotion:create", idempotencyKey,
				CatalogCommandIdempotencySupport.hash(slug, name, description, values.discountType(), values.discountValue(), values.currency(), startsAt, endsAt, values.minimumQuantity(), values.stackingPolicy(), priority, products, categories, clients, normalizedRules), candidate);
        if (!id.equals(candidate)) return promotion(scope, id);
        products.forEach(value -> requireProduct(scope, value));
        categories.forEach(value -> requireCategory(scope, value));
        clients.forEach(value -> requireClientAccount(scope, value));
        Timestamp now = now();
		jdbc.update("insert into catalog_management.promotion (id,tenant_id,workspace_id,slug,name,description,status,discount_type,discount_value,currency,starts_at,ends_at,minimum_quantity,stacking_policy,priority,version,created_at,updated_at) values (?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?, ?,0,?,?)",
				id, scope.tenantId(), scope.workspaceId(), required(slug, 140), required(name, 200), optional(description, 2000),
				values.discountType(), values.discountValue(), values.currency(), timestamp(startsAt), timestamp(endsAt),
				values.minimumQuantity(), values.stackingPolicy(), priority, now, now);
        writeTargets(scope, id, products, categories, clients, normalizedRules);
        return promotion(scope, id);
    }

    @Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version) {
        requirePromotion(scope, id);
        return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), version);
    }

    @Override
    public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version) {
		return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, version, 0);
	}

	@Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version, int priority) {
        requirePromotion(scope, id);
        List<UUID> products = distinct(productIds);
        List<UUID> categories = distinct(categoryIds);
        List<UUID> clients = distinct(clientAccountIds);
        List<CatalogManagementModels.PromotionRuleView> normalizedRules = rules == null ? List.of() : List.copyOf(rules);
        products.forEach(value -> requireProduct(scope, value));
        categories.forEach(value -> requireCategory(scope, value));
        clients.forEach(value -> requireClientAccount(scope, value));
		PromotionValues values = values(discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
		if (priority < -1_000_000 || priority > 1_000_000) throw new IllegalArgumentException("Promotion priority is invalid");
		int updated = jdbc.update("update catalog_management.promotion set slug=?,name=?,description=?,discount_type=?,discount_value=?,currency=?,starts_at=?,ends_at=?,minimum_quantity=?,stacking_policy=?,priority=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
				required(slug, 140), required(name, 200), optional(description, 2000), values.discountType(), values.discountValue(),
				values.currency(), timestamp(startsAt), timestamp(endsAt), values.minimumQuantity(), values.stackingPolicy(), priority, now(),
                scope.tenantId(), scope.workspaceId(), id, version);
        if (updated == 0) throw new CatalogConcurrencyException();
        writeTargets(scope, id, products, categories, clients, normalizedRules);
        return promotion(scope, id);
    }

    @Override
    public CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version) {
        String normalized = enumValue(status, "status", PromotionStatus.values());
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
		List<CatalogManagementModels.PromotionView> result = jdbc.query("select id,slug,name,description,status,discount_type,discount_value,currency,starts_at,ends_at,minimum_quantity,stacking_policy,priority,version from catalog_management.promotion where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> view(scope, rs), scope.tenantId(), scope.workspaceId(), id);
        if (result.isEmpty()) throw new CatalogResourceNotFoundException("promotion");
        return result.getFirst();
	}

	private Map<UUID, List<String>> targetStrings(CatalogScope scope, String table, String column, List<UUID> promotionIds) {
		if (promotionIds.isEmpty()) return Map.of();
		String placeholders = promotionIds.stream().map(value -> "?").collect(java.util.stream.Collectors.joining(","));
		List<Object> parameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
		parameters.addAll(promotionIds);
		Map<UUID, List<String>> grouped = new LinkedHashMap<>();
		jdbc.query("select promotion_id," + column + " from catalog_management." + table + " where tenant_id=? and workspace_id=? and promotion_id in (" + placeholders + ") order by promotion_id," + column,
				(rs, row) -> {
					grouped.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(rs.getObject(2, UUID.class).toString());
					return null;
				}, parameters.toArray());
		return grouped;
	}

	private Map<UUID, List<CatalogManagementModels.PromotionRuleView>> targetRules(CatalogScope scope, List<UUID> promotionIds) {
		if (promotionIds.isEmpty()) return Map.of();
		String placeholders = promotionIds.stream().map(value -> "?").collect(java.util.stream.Collectors.joining(","));
		List<Object> parameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
		parameters.addAll(promotionIds);
		Map<UUID, List<CatalogManagementModels.PromotionRuleView>> grouped = new LinkedHashMap<>();
		jdbc.query("select promotion_id,rule_type,rule_value from catalog_management.promotion_rule where tenant_id=? and workspace_id=? and promotion_id in (" + placeholders + ") order by promotion_id,rule_type,rule_value",
				(rs, row) -> {
					grouped.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
							.add(new CatalogManagementModels.PromotionRuleView(rs.getString(2), rs.getString(3)));
					return null;
				}, parameters.toArray());
		return grouped;
	}

    private CatalogManagementModels.PromotionView view(CatalogScope scope, ResultSet rs) throws SQLException {
        UUID promotionId = rs.getObject("id", UUID.class);
        List<String> products = jdbc.query("select product_id from catalog_management.promotion_product where tenant_id=? and workspace_id=? and promotion_id=? order by product_id",
                (mapping, row) -> mapping.getObject(1, UUID.class).toString(), scope.tenantId(), scope.workspaceId(), promotionId);
        List<String> categories = jdbc.query("select category_id from catalog_management.promotion_category where tenant_id=? and workspace_id=? and promotion_id=? order by category_id",
                (mapping, row) -> mapping.getObject(1, UUID.class).toString(), scope.tenantId(), scope.workspaceId(), promotionId);
        List<String> clients = jdbc.query("select client_account_id from catalog_management.promotion_client_account where tenant_id=? and workspace_id=? and promotion_id=? order by client_account_id",
                (mapping, row) -> mapping.getObject(1, UUID.class).toString(), scope.tenantId(), scope.workspaceId(), promotionId);
        List<CatalogManagementModels.PromotionRuleView> rules = jdbc.query("select rule_type,rule_value from catalog_management.promotion_rule where tenant_id=? and workspace_id=? and promotion_id=? order by rule_type,rule_value",
                (mapping, row) -> new CatalogManagementModels.PromotionRuleView(mapping.getString(1), mapping.getString(2)), scope.tenantId(), scope.workspaceId(), promotionId);
		return new CatalogManagementModels.PromotionView(promotionId.toString(), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
				rs.getString("status"), rs.getString("discount_type"), rs.getBigDecimal("discount_value"), strip(rs.getString("currency")),
				instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")), rs.getBigDecimal("minimum_quantity"),
				rs.getString("stacking_policy"), rs.getInt("priority"), products, categories, clients, rules, rs.getLong("version"));
    }

    private void writeTargets(CatalogScope scope, UUID promotionId, List<UUID> products, List<UUID> categories,
            List<UUID> clients, List<CatalogManagementModels.PromotionRuleView> rules) {
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
        jdbc.update("delete from catalog_management.promotion_client_account where tenant_id=? and workspace_id=? and promotion_id=?",
                scope.tenantId(), scope.workspaceId(), promotionId);
        for (UUID clientId : clients) {
            jdbc.update("insert into catalog_management.promotion_client_account (promotion_id,tenant_id,workspace_id,client_account_id) values (?,?,?,?)",
                    promotionId, scope.tenantId(), scope.workspaceId(), clientId);
        }
        jdbc.update("delete from catalog_management.promotion_rule where tenant_id=? and workspace_id=? and promotion_id=?",
                scope.tenantId(), scope.workspaceId(), promotionId);
        for (CatalogManagementModels.PromotionRuleView rule : rules) {
            String type = enumValue(rule.type(), "ruleType", "MIN_ORDER_AMOUNT", "CLIENT_ACCOUNT", "CLIENT_ACCOUNT_ID", "CLIENT_SEGMENT", "BUYER_TIER", "CURRENCY");
            String value = required(rule.value(), 255);
            jdbc.update("insert into catalog_management.promotion_rule (id,promotion_id,tenant_id,workspace_id,rule_type,rule_value) values (?,?,?,?,?,?)",
                    UUID.randomUUID(), promotionId, scope.tenantId(), scope.workspaceId(), type, value);
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

    private void requireClientAccount(CatalogScope scope, UUID id) {
        if (jdbc.queryForObject("select count(*) from sales.client_account where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                Integer.class, scope.tenantId(), scope.workspaceId(), id) != 1) throw new CatalogResourceNotFoundException("client account");
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
	private record PromotionRow(UUID id, String slug, String name, String description, String status, String discountType,
			BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
			String stackingPolicy, int priority, long version) { }
}
