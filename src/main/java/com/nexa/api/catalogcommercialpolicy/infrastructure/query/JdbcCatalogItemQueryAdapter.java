package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingView;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField;
import com.nexa.api.catalogcommercialpolicy.application.model.SortDirection;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.AuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AuthoritativeOfferQuery offers;
    private final Clock clock;

    @Autowired
    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability, AuthoritativeOfferQuery offers) {
        this(jdbc, availability, offers, Clock.systemUTC());
    }

    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability) {
        this(jdbc, availability, new JdbcAuthoritativeOfferQuery(jdbc), Clock.systemUTC());
    }

    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability, Clock clock) {
        this(jdbc, availability, new JdbcAuthoritativeOfferQuery(jdbc), clock);
    }

    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability, AuthoritativeOfferQuery offers, Clock clock) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.offers = offers;
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
        return findByCatalogItemId(scope, id, BigDecimal.ONE);
    }

    @Override
    public Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId id, BigDecimal quantity) {
        String predicate = " where s.tenant_id=? and s.workspace_id=? and s.legacy_catalog_item_id=? and s.status='ACTIVE' and s.visible=true"
                + buyerVisibility(scope);
        List<Row> rows = jdbc.query(selectSql() + fromClause() + predicate, this::row,
                scope.tenantId(), scope.workspaceId(), id.value());
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(detail(rows.getFirst(), enrich(scope, rows,
                Map.of(rows.getFirst().sellableSkuId(), quantity == null ? BigDecimal.ONE : quantity))));
    }

    @Override
    public List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> ids) {
        return findByCatalogItemIds(scope, ids, Map.of());
    }

    @Override
    public List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> ids,
                                                         Map<String, BigDecimal> quantitiesByCatalogItemId) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> values = ids.stream().filter(java.util.Objects::nonNull).map(CatalogItemId::value).distinct().toList();
        if (values.isEmpty()) return List.of();
        String placeholders = values.stream().map(ignored -> "?").collect(Collectors.joining(","));
        String predicate = " where s.tenant_id=? and s.workspace_id=? and s.legacy_catalog_item_id in (" + placeholders + ")"
                + " and s.status='ACTIVE' and s.visible=true" + buyerVisibility(scope);
        List<Object> parameters = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        parameters.addAll(values);
        List<Row> rows = jdbc.query(selectSql() + fromClause() + predicate, this::row, parameters.toArray());
        Map<UUID, BigDecimal> quantities = new java.util.HashMap<>();
        for (Row row : rows) {
            BigDecimal quantity = quantitiesByCatalogItemId == null ? null : quantitiesByCatalogItemId.get(row.catalogItemId());
            quantities.put(row.sellableSkuId(), quantity == null ? BigDecimal.ONE : quantity);
        }
        Enrichment enrichment = enrich(scope, rows, quantities);
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
                row.variantCode(), row.variantName(), available.sellableAvailability());
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
                row.variantCode(), row.variantName(), available.sellableAvailability());
    }

    private String promotionLabel(String catalogItemId, Enrichment enrichment) {
        return enrichment.pricing().getOrDefault(catalogItemId,
                        CatalogPricingView.base(BigDecimal.ZERO, "PEN", clock.instant()))
                .appliedPromotions().stream().map(CatalogPricingView.AppliedPromotion::name)
                .filter(name -> name != null && !name.isBlank()).collect(Collectors.joining(", "));
    }

    private Enrichment enrich(CatalogScope scope, List<Row> rows) {
        return enrich(scope, rows, Map.of());
    }

    private Enrichment enrich(CatalogScope scope, List<Row> rows, Map<UUID, BigDecimal> requestedQuantities) {
        if (rows.isEmpty()) return new Enrichment(Map.of(), Map.of());
        List<String> catalogItemIds = rows.stream().map(Row::catalogItemId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, ProductAvailabilityPort.Snapshot> availabilityById = catalogItemIds.isEmpty() ? Map.of()
                : availability.find(scope, catalogItemIds).stream().collect(Collectors.toMap(
                        ProductAvailabilityPort.Snapshot::catalogItemId, value -> value, (left, right) -> left));
        Instant asOf = clock.instant();
        List<UUID> skuIds = rows.stream().map(Row::sellableSkuId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, BigDecimal> quantities = skuIds.stream().collect(Collectors.toUnmodifiableMap(id -> id,
                id -> requestedQuantities.getOrDefault(id, BigDecimal.ONE)));
        Map<UUID, AuthoritativeOfferQuery.Offer> resolved = offers.resolve(scope.tenantId(), scope.workspaceId(), skuIds,
                scope.clientAccountId(), scope.clientAccountSegment(), scope.buyerTier(), quantities, asOf);
        Map<String, CatalogPricingView> prices = new java.util.HashMap<>();
        for (Row row : rows) {
            AuthoritativeOfferQuery.Offer offer = resolved.get(row.sellableSkuId());
            if (offer == null) {
                if (scope.buyerView() || scope.clientAccountId() != null) {
                    throw new IllegalStateException("Authoritative offer is unavailable for the customer account");
                }
                prices.put(row.catalogItemId(), new CatalogPricingView(row.amount(), row.amount(), BigDecimal.ZERO,
                        row.currency(), List.of(), asOf, scope.buyerView()));
                continue;
            }
            prices.put(row.catalogItemId(), new CatalogPricingView(offer.basePrice(), offer.effectivePrice(),
                    offer.discountAmount(), offer.currency(), offer.appliedPromotions().stream()
                    .map(value -> new CatalogPricingView.AppliedPromotion(value.id().toString(), value.name(),
                            value.discountType(), value.discountAmount())).toList(), offer.effectiveAt(), scope.buyerView()));
        }
        return new Enrichment(availabilityById, prices);
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
        sql.append(buyerVisibility(scope));
        if (criteria.query() != null && !criteria.query().isBlank()) sql.append(" and (lower(f.name) like lower(?) or lower(coalesce(v.name,'')) like lower(?) or lower(coalesce(p.name,'')) like lower(?) or lower(coalesce(p.description,'')) like lower(?) or lower(s.sku_code) like lower(?) or lower(s.presentation) like lower(?) or lower(coalesce(s.legacy_catalog_item_id,'')) like lower(?))");
        if (criteria.brand() != null) sql.append(" and lower(b.name) like lower(?)");
        if (criteria.category() != null) sql.append(" and lower(c.name) like lower(?)");
        if (criteria.coldChainRequirement() != null) sql.append(" and f.storage_family=?");
        return sql.toString();
    }

    private static String buyerVisibility(CatalogScope scope) {
        return scope.buyerView()
                ? " and pv.buyer_visible=true and f.status='ACTIVE' and (s.legacy_product_id is null or p.status='ACTIVE')"
                : "";
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
                              Map<String, CatalogPricingView> pricing) { }
}
