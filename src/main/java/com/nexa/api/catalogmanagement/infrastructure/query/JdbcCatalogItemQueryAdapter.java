package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogPricingView;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogSortField;
import com.nexa.api.catalogmanagement.application.model.SortDirection;
import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogmanagement.domain.model.pricing.EffectivePricePolicy;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate.PromotionRule;
import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogItemQueryAdapter implements CatalogItemQueryPort {
    private final JdbcTemplate jdbc;
    private final ProductAvailabilityPort availability;
    private final Clock clock;
    private final EffectivePricePolicy pricing = new EffectivePricePolicy();

    @Autowired
    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability) {
        this(jdbc, availability, Clock.systemUTC());
    }

    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability, Clock clock) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.clock = clock;
    }

    @Override
    public CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria) {
        throw new IllegalStateException("Catalog scope is required for persistent queries");
    }

    @Override
    public Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId id) {
        return Optional.empty();
    }

    @Override
    public CatalogPage<CatalogItemSummary> search(CatalogScope scope, CatalogSearchCriteria criteria) {
        String predicate = predicate(scope, criteria);
        List<Object> baseArgs = args(scope, criteria);
        String order = switch (criteria.sortField()) {
            case ITEM_NAME -> "f.name";
            case BRAND_NAME -> "b.name";
            case CATEGORY_NAME -> "c.name";
            case UNIT_PRICE -> "coalesce(current_price.amount,0)";
        };
        String direction = criteria.sortDirection() == SortDirection.DESC ? " desc" : " asc";
        String sql = selectSql() + fromClause() + predicate + " order by " + order + direction
                + ",s.legacy_catalog_item_id asc nulls last,s.id asc limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(baseArgs);
        pageArgs.add(criteria.size());
        pageArgs.add((long) criteria.page() * criteria.size());
        List<Row> rows = jdbc.query(sql, this::row, pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) " + fromClause() + predicate, Long.class, baseArgs.toArray());
        Enrichment enrichment = enrich(scope, rows);
        List<CatalogItemSummary> items = rows.stream().map(row -> summary(row, enrichment)).toList();
        return new CatalogPage<>(items, criteria.page(), criteria.size(), total == null ? 0 : total,
                criteria.sortField(), criteria.sortDirection());
    }

    @Override
    public Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId id) {
        String predicate = " where s.tenant_id=? and s.workspace_id=? and s.legacy_catalog_item_id=? and s.status='ACTIVE' and s.visible=true"
                + (scope.buyerView() ? " and pv.buyer_visible=true" : "");
        List<Row> rows = jdbc.query(selectSql() + fromClause() + predicate, this::row,
                scope.tenantId(), scope.workspaceId(), id.value());
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(detail(rows.getFirst(), enrich(scope, rows)));
    }

    @Override
    public List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> values = ids.stream().filter(java.util.Objects::nonNull).map(CatalogItemId::value).distinct().toList();
        if (values.isEmpty()) return List.of();
        String placeholders = values.stream().map(ignored -> "?").collect(Collectors.joining(","));
        String predicate = " where s.tenant_id=? and s.workspace_id=? and s.legacy_catalog_item_id in (" + placeholders + ")"
                + " and s.status='ACTIVE' and s.visible=true" + (scope.buyerView() ? " and pv.buyer_visible=true" : "");
        List<Object> parameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        parameters.addAll(values);
        List<Row> rows = jdbc.query(selectSql() + fromClause() + predicate, this::row, parameters.toArray());
        Enrichment enrichment = enrich(scope, rows);
        return rows.stream().map(row -> detail(row, enrichment)).toList();
    }

    private CatalogItemSummary summary(Row row, Enrichment enrichment) {
        CatalogPricingView value = enrichment.pricing().getOrDefault(row.catalogItemId(),
                CatalogPricingView.base(row.amount(), row.currency(), clock.instant()));
        ProductAvailabilityPort.Snapshot available = enrichment.availability().getOrDefault(row.catalogItemId(), unknown(row.catalogItemId()));
        String label = promotionLabel(row.catalogItemId(), enrichment);
        return new CatalogItemSummary(row.catalogItemId(), row.productId().toString(), row.itemName(), row.brandName(),
                row.categoryName(), row.presentation(), value.effectivePrice(), value.currency(), row.temperature(),
                row.imagePath(), row.imageFileName(), row.status(), available.status(), available.nearExpiry(), label, value,
                string(row.familyId()), row.familyCode(), row.familyName(), row.sellableSkuId().toString(), row.skuCode(),
                row.unitOfMeasure(), row.packagingType(), row.netWeight(), row.grossWeight(), available.asOf(),
                row.variantCode(), row.variantName());
    }

    private CatalogItemDetail detail(Row row, Enrichment enrichment) {
        CatalogPricingView value = enrichment.pricing().getOrDefault(row.catalogItemId(),
                CatalogPricingView.base(row.amount(), row.currency(), clock.instant()));
        ProductAvailabilityPort.Snapshot available = enrichment.availability().getOrDefault(row.catalogItemId(), unknown(row.catalogItemId()));
        String label = promotionLabel(row.catalogItemId(), enrichment);
        return new CatalogItemDetail(row.catalogItemId(), row.productId().toString(), row.itemName(), row.brandName(),
                row.categoryName(), row.description(), row.presentation(), value.effectivePrice(), value.currency(),
                row.temperature(), row.imagePath(), row.imageFileName(), row.status(), available.status(), available.nearExpiry(),
                label, value, string(row.familyId()), row.familyCode(), row.familyName(), row.sellableSkuId().toString(), row.skuCode(),
                row.unitOfMeasure(), row.packagingType(), row.netWeight(), row.grossWeight(), available.asOf(),
                row.variantCode(), row.variantName());
    }

    private String promotionLabel(String catalogItemId, Enrichment enrichment) {
        return enrichment.promotions().getOrDefault(catalogItemId, List.of()).stream()
                .map(PromotionCandidate::name).filter(name -> !name.isBlank()).collect(Collectors.joining(", "));
    }

    private Enrichment enrich(CatalogScope scope, List<Row> rows) {
        if (rows.isEmpty()) return new Enrichment(Map.of(), Map.of(), Map.of());
        List<String> catalogItemIds = rows.stream().map(Row::catalogItemId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, ProductAvailabilityPort.Snapshot> availabilityById = catalogItemIds.isEmpty() ? Map.of()
                : availability.find(scope, catalogItemIds).stream().collect(Collectors.toMap(
                        ProductAvailabilityPort.Snapshot::catalogItemId, value -> value, (left, right) -> left));
        Map<String, List<PromotionCandidate>> promotionsById = batchPromotions(scope, rows);
        Instant asOf = clock.instant();
        Map<String, CatalogPricingView> prices = new HashMap<>();
        for (Row row : rows) {
            List<PromotionCandidate> candidates = scope.buyerView()
                    ? promotionsById.getOrDefault(row.catalogItemId(), List.of()) : List.of();
            EffectivePricePolicy.Result result = pricing.calculate(row.amount(), row.currency(), BigDecimal.ONE,
                    scope.clientAccountSegment(), scope.buyerTier(), candidates, asOf);
            prices.put(row.catalogItemId(), new CatalogPricingView(result.basePrice(), result.effectivePrice(),
                    result.discountAmount(), row.currency(), result.appliedPromotions().stream()
                            .map(value -> new CatalogPricingView.AppliedPromotion(value.id().toString(), value.name(),
                                    value.discountType(), value.discountAmount())).toList(), asOf));
        }
        return new Enrichment(availabilityById, promotionsById, prices);
    }

    private Map<String, List<PromotionCandidate>> batchPromotions(CatalogScope scope, List<Row> rows) {
        List<UUID> skuIds = rows.stream().map(Row::sellableSkuId).filter(java.util.Objects::nonNull).distinct().toList();
        if (skuIds.isEmpty()) return Map.of();
        String placeholders = skuIds.stream().map(value -> "?").collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        parameters.addAll(skuIds);
        if (scope.clientAccountId() != null) parameters.add(scope.clientAccountId());
        String clientPredicate = scope.clientAccountId() == null
                ? " and not exists (select 1 from catalog_management.promotion_client_account pca0 where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id)"
                : " and (not exists (select 1 from catalog_management.promotion_client_account pca0 where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id) or exists (select 1 from catalog_management.promotion_client_account pca1 where pca1.tenant_id=pr.tenant_id and pca1.workspace_id=pr.workspace_id and pca1.promotion_id=pr.id and pca1.client_account_id=?))";
        String sql = "select s.legacy_catalog_item_id,pr.id,pr.name,pr.slug,pr.discount_type,pr.discount_value,pr.currency,pr.starts_at,pr.ends_at,pr.minimum_quantity,pr.stacking_policy,pr.status,pr.priority "
                + "from catalog_management.sellable_sku s join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                + "join catalog_management.promotion pr on pr.tenant_id=s.tenant_id and pr.workspace_id=s.workspace_id "
                + "where s.tenant_id=? and s.workspace_id=? and s.id in (" + placeholders + ") and pr.status='ACTIVE' "
                + "and (pr.starts_at is null or pr.starts_at<=current_timestamp) and (pr.ends_at is null or pr.ends_at>current_timestamp) "
                + "and (exists (select 1 from catalog_management.promotion_sku ps where ps.tenant_id=pr.tenant_id and ps.workspace_id=pr.workspace_id and ps.promotion_id=pr.id and ps.sku_id=s.id) "
                + "or exists (select 1 from catalog_management.promotion_category ppc where ppc.tenant_id=pr.tenant_id and ppc.workspace_id=pr.workspace_id and ppc.promotion_id=pr.id and ppc.category_id=f.category_id))"
                + clientPredicate + " order by s.legacy_catalog_item_id,pr.id";
        Map<String, List<PromotionCandidate>> result = new HashMap<>();
        jdbc.query(sql, (rs, row) -> {
            String itemId = rs.getString(1);
            PromotionCandidate candidate = new PromotionCandidate(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    Promotion.DiscountType.valueOf(rs.getString(5)), rs.getBigDecimal(6), rs.getString(7),
                    instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)), rs.getBigDecimal(10),
                    Promotion.StackingPolicy.valueOf(rs.getString(11)), PromotionStatus.valueOf(rs.getString(12)), rs.getInt(13), List.of(), List.of());
            result.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(candidate);
            return null;
        }, parameters.toArray());
        if (result.isEmpty()) return result;
        String promotionPlaceholders = result.values().stream().flatMap(List::stream).map(value -> "?").collect(Collectors.joining(","));
        List<Object> ruleParameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        ruleParameters.addAll(result.values().stream().flatMap(List::stream).map(PromotionCandidate::id).toList());
        Map<UUID, List<PromotionRule>> rules = new HashMap<>();
        jdbc.query("select promotion_id,rule_type,rule_value from catalog_management.promotion_rule where tenant_id=? and workspace_id=? and promotion_id in (" + promotionPlaceholders + ") order by promotion_id,rule_type,rule_value",
                (rs, row) -> {
                    rules.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(new PromotionRule(rs.getString(2), rs.getString(3)));
                    return null;
                }, ruleParameters.toArray());
        for (Map.Entry<String, List<PromotionCandidate>> entry : result.entrySet()) {
            entry.setValue(entry.getValue().stream().map(candidate -> new PromotionCandidate(candidate.id(), candidate.name(), candidate.stableCode(),
                    candidate.discountType(), candidate.discountValue(), candidate.currency(), candidate.startsAt(), candidate.endsAt(),
                    candidate.minimumQuantity(), candidate.stackingPolicy(), candidate.status(), candidate.priority(), candidate.clientAccountIds(),
                    rules.getOrDefault(candidate.id(), List.of()))).toList());
        }
        return result;
    }

    private String selectSql() {
        return "select s.id sellable_sku_id,coalesce(s.legacy_product_id,s.id) product_id,s.legacy_catalog_item_id catalog_item_id,s.sku_code product_code," +
                "coalesce(f.name,p.name) item_name,f.id family_id,f.family_code family_code,f.name family_name,v.variant_code variant_code,v.name variant_name,c.id category_id,c.name category_name," +
                "b.name brand_name,s.presentation presentation,coalesce(p.description,f.description) description,f.storage_family temperature,s.status status," +
                "coalesce(current_price.amount,0) amount,coalesce(current_price.currency,'PEN') currency,s.unit_of_measure unit_of_measure,s.packaging_type packaging_type," +
                "s.net_weight net_weight,s.gross_weight gross_weight,asset.asset_path image_path,asset.file_name image_file_name ";
    }

    private String fromClause() {
        return "from catalog_management.sellable_sku s " +
                "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id " +
                "left join catalog_management.product_variant v on v.tenant_id=s.tenant_id and v.workspace_id=s.workspace_id and v.id=s.variant_id " +
                "left join catalog_management.brand b on b.tenant_id=f.tenant_id and b.workspace_id=f.workspace_id and b.id=f.brand_id " +
                "left join catalog_management.category c on c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.id=f.category_id " +
                "left join catalog_management.product p on p.tenant_id=s.tenant_id and p.workspace_id=s.workspace_id and p.id=s.legacy_product_id " +
                "left join catalog_management.product_visibility pv on pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id and pv.product_id=p.id " +
                "left join lateral (select amount,currency from catalog_management.sku_price pr where pr.tenant_id=s.tenant_id and pr.workspace_id=s.workspace_id and pr.sku_id=s.id and pr.cancelled_at is null and pr.valid_from<=current_timestamp and (pr.valid_until is null or pr.valid_until>current_timestamp) order by pr.valid_from desc,pr.id desc limit 1) current_price on true " +
                "left join lateral (select asset_path,file_name from catalog_management.product_asset_reference pa where pa.tenant_id=s.tenant_id and pa.workspace_id=s.workspace_id and pa.product_id=s.legacy_product_id order by pa.sort_order,pa.id limit 1) asset on true";
    }

    private String predicate(CatalogScope scope, CatalogSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(" where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible=true");
        if (scope.buyerView()) sql.append(" and pv.buyer_visible=true");
        if (criteria.query() != null && !criteria.query().isBlank()) sql.append(" and (lower(f.name) like lower(?) or lower(coalesce(v.name,'')) like lower(?) or lower(coalesce(p.name,'')) like lower(?) or lower(coalesce(p.description,'')) like lower(?) or lower(s.sku_code) like lower(?) or lower(s.presentation) like lower(?) or lower(coalesce(s.legacy_catalog_item_id,'')) like lower(?))");
        if (criteria.brand() != null) sql.append(" and lower(b.name) like lower(?)");
        if (criteria.category() != null) sql.append(" and lower(c.name) like lower(?)");
        if (criteria.coldChainRequirement() != null) sql.append(" and f.storage_family=?");
        return sql.toString();
    }

    private List<Object> args(CatalogScope scope, CatalogSearchCriteria criteria) {
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        if (criteria.query() != null && !criteria.query().isBlank()) {
            String value = "%" + criteria.query() + "%";
            args.add(value); args.add(value); args.add(value); args.add(value); args.add(value); args.add(value); args.add(value);
        }
        if (criteria.brand() != null) args.add("%" + criteria.brand() + "%");
        if (criteria.category() != null) args.add("%" + criteria.category() + "%");
        if (criteria.coldChainRequirement() != null) args.add(criteria.coldChainRequirement().name());
        return args;
    }

    private Row row(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Row(rs.getObject("sellable_sku_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("catalog_item_id"), rs.getString("product_code"), rs.getString("item_name"),
                rs.getObject("family_id", UUID.class), rs.getString("family_code"), rs.getString("family_name"),
                rs.getString("variant_code"), rs.getString("variant_name"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getString("brand_name"),
                rs.getString("presentation"), rs.getString("description"), rs.getString("temperature"), rs.getString("status"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("unit_of_measure"), rs.getString("packaging_type"),
                rs.getBigDecimal("net_weight"), rs.getBigDecimal("gross_weight"), rs.getString("image_path"), rs.getString("image_file_name"));
    }

    private static String string(UUID value) { return value == null ? null : value.toString(); }
    private static ProductAvailabilityPort.Snapshot unknown(String id) { return new ProductAvailabilityPort.Snapshot(id, "UNKNOWN", false, Instant.EPOCH); }
    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    private record Row(UUID sellableSkuId, UUID productId, String catalogItemId, String skuCode, String itemName,
                       UUID familyId, String familyCode, String familyName, String variantCode, String variantName,
                       UUID categoryId, String categoryName,
                       String brandName, String presentation, String description, String temperature, String status,
                       BigDecimal amount, String currency, String unitOfMeasure, String packagingType,
                       BigDecimal netWeight, BigDecimal grossWeight, String imagePath, String imageFileName) { }

    private record Enrichment(Map<String, ProductAvailabilityPort.Snapshot> availability,
                              Map<String, List<PromotionCandidate>> promotions,
                              Map<String, CatalogPricingView> pricing) { }
}
