package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.AuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.AuthoritativePriceResolver;
import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.EffectivePricePolicy;
import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.PromotionCandidate;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
public class JdbcAuthoritativeOfferQuery implements AuthoritativeOfferQuery {
    private final JdbcTemplate jdbc;
    private final AuthoritativePriceResolver resolver = new AuthoritativePriceResolver();

    public JdbcAuthoritativeOfferQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Offer> resolve(UUID tenantId, UUID workspaceId, List<UUID> skuIds,
                                    UUID customerAccountId, String clientSegment, String buyerTier,
                                    Map<UUID, BigDecimal> quantities, Instant effectiveAt) {
        Objects.requireNonNull(tenantId, "Offer tenant id is required");
        Objects.requireNonNull(workspaceId, "Offer workspace id is required");
        Objects.requireNonNull(effectiveAt, "Offer effective instant is required");
        List<UUID> ids = skuIds == null ? List.of() : skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String resolvedSegment = customerAccountId == null ? null
                : activeClientSegment(tenantId, workspaceId, customerAccountId);
        if (customerAccountId != null && resolvedSegment == null) return Map.of();

        Map<UUID, BasePrice> basePrices = basePrices(tenantId, workspaceId, ids, effectiveAt);
        AuthoritativePriceResolver.CustomerTerms customerTerms = customerAccountId == null
                ? null : customerTerms(tenantId, workspaceId, customerAccountId, effectiveAt);
        Map<UUID, AuthoritativePriceResolver.PriceListPrice> listPrices = customerTerms == null
                || customerTerms.priceListId() == null ? Map.of()
                : priceListPrices(tenantId, workspaceId, customerTerms.priceListId(), ids, effectiveAt);
        Map<UUID, List<PromotionCandidate>> promotions = promotions(
                tenantId, workspaceId, ids, customerAccountId, effectiveAt);
        String segment = resolvedSegment == null ? clientSegment : resolvedSegment;

        Map<UUID, Offer> result = new LinkedHashMap<>();
        for (UUID skuId : ids) {
            BasePrice base = basePrices.get(skuId);
            if (base == null) continue;
            BigDecimal quantity = quantities == null ? null : quantities.get(skuId);
            if (quantity == null) quantity = BigDecimal.ONE;
            AuthoritativePriceResolver.ResolvedOffer resolved = resolver.resolve(base.amount(), base.currency(),
                    listPrices.get(skuId), customerTerms, customerAccountId, segment, buyerTier, quantity,
                    promotions.getOrDefault(skuId, List.of()), effectiveAt);
            result.put(skuId, new Offer(resolved.basePrice(), resolved.effectivePrice(), resolved.discountAmount(),
                    resolved.currency(), resolved.appliedPromotions(), resolved.effectiveAt()));
        }
        return Map.copyOf(result);
    }

    private Map<UUID, BasePrice> basePrices(UUID tenantId, UUID workspaceId, List<UUID> ids, Instant asOf) {
        String sql = "select sku_id,amount,currency from catalog_management.sku_price "
                + "where tenant_id=? and workspace_id=? and sku_id in (" + placeholders(ids.size()) + ") "
                + "and cancelled_at is null and valid_from<=? and (valid_until is null or valid_until>?) "
                + "order by sku_id,valid_from desc,id";
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(ids);
        parameters.add(Timestamp.from(asOf));
        parameters.add(Timestamp.from(asOf));
        List<PriceRow> rows = jdbc.query(sql, (rs, row) -> new PriceRow(
                rs.getObject("sku_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency")), parameters.toArray());
        Map<UUID, BasePrice> result = new HashMap<>();
        for (PriceRow row : rows) {
            if (result.putIfAbsent(row.skuId(), new BasePrice(row.amount(), row.currency())) != null) {
                throw new IllegalStateException("Multiple active Base Prices exist for one SKU");
            }
        }
        return result;
    }

    private AuthoritativePriceResolver.CustomerTerms customerTerms(
            UUID tenantId, UUID workspaceId, UUID customerAccountId, Instant asOf) {
        List<TermsRow> rows = jdbc.query("select terms_id,price_list_id,currency from catalog_management.customer_terms "
                        + "where tenant_id=? and workspace_id=? and customer_account_id=? and valid_from<=? "
                        + "and (valid_to is null or valid_to>?)",
                (rs, row) -> new TermsRow(rs.getObject("terms_id", UUID.class),
                        rs.getObject("price_list_id", UUID.class), rs.getString("currency")),
                tenantId, workspaceId, customerAccountId, Timestamp.from(asOf), Timestamp.from(asOf));
        if (rows.size() > 1) throw new IllegalStateException("Multiple Customer Terms are effective for one account");
        return rows.isEmpty() ? null : new AuthoritativePriceResolver.CustomerTerms(
                rows.getFirst().priceListId(), rows.getFirst().currency());
    }

    private Map<UUID, AuthoritativePriceResolver.PriceListPrice> priceListPrices(
            UUID tenantId, UUID workspaceId, UUID priceListId, List<UUID> skuIds, Instant asOf) {
        String sql = "select i.sku_id,i.price_list_id,i.unit_price,i.currency item_currency,l.currency list_currency "
                + "from catalog_management.price_list l join catalog_management.price_list_item i "
                + "on i.price_list_id=l.price_list_id and i.tenant_id=l.tenant_id and i.workspace_id=l.workspace_id "
                + "where l.tenant_id=? and l.workspace_id=? and l.price_list_id=? and l.status='ACTIVE' "
                + "and (l.valid_from is null or l.valid_from<=?) and (l.valid_to is null or l.valid_to>?) "
                + "and i.sku_id in (" + placeholders(skuIds.size()) + ") "
                + "and (i.valid_from is null or i.valid_from<=?) and (i.valid_to is null or i.valid_to>?) "
                + "order by i.sku_id";
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId, priceListId,
                Timestamp.from(asOf), Timestamp.from(asOf)));
        parameters.addAll(skuIds);
        parameters.add(Timestamp.from(asOf));
        parameters.add(Timestamp.from(asOf));
        Map<UUID, AuthoritativePriceResolver.PriceListPrice> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            while (rs.next()) {
                String itemCurrency = rs.getString("item_currency").trim();
                String listCurrency = rs.getString("list_currency").trim();
                if (!itemCurrency.equalsIgnoreCase(listCurrency)) {
                    throw new IllegalStateException("Price List item currency does not match Price List currency");
                }
                result.put(rs.getObject("sku_id", UUID.class), new AuthoritativePriceResolver.PriceListPrice(
                        rs.getObject("price_list_id", UUID.class), rs.getBigDecimal("unit_price"), itemCurrency));
            }
            return null;
        }, parameters.toArray());
        return result;
    }

    private Map<UUID, List<PromotionCandidate>> promotions(UUID tenantId, UUID workspaceId, List<UUID> skuIds,
                                                           UUID customerAccountId, Instant asOf) {
        String clientPredicate = customerAccountId == null
                ? " and not exists (select 1 from catalog_management.promotion_client_account pca0 "
                    + "where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id)"
                : " and (not exists (select 1 from catalog_management.promotion_client_account pca0 "
                    + "where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id) "
                    + "or exists (select 1 from catalog_management.promotion_client_account pca1 "
                    + "where pca1.tenant_id=pr.tenant_id and pca1.workspace_id=pr.workspace_id and pca1.promotion_id=pr.id "
                    + "and pca1.client_account_id=?))";
        String sql = "select s.id sku_id,pr.id promotion_id,pr.name,pr.slug,pr.discount_type,pr.discount_value,pr.currency,"
                + "pr.starts_at,pr.ends_at,pr.minimum_quantity,pr.stacking_policy,pr.status,pr.priority "
                + "from catalog_management.sellable_sku s join catalog_management.product_family f "
                + "on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                + "join catalog_management.promotion pr on pr.tenant_id=s.tenant_id and pr.workspace_id=s.workspace_id "
                + "where s.tenant_id=? and s.workspace_id=? and s.id in (" + placeholders(skuIds.size()) + ") "
                + "and pr.status in ('ACTIVE','SCHEDULED') and (pr.starts_at is null or pr.starts_at<=?) "
                + "and (pr.ends_at is null or pr.ends_at>?) and ("
                + "exists (select 1 from catalog_management.promotion_sku ps where ps.tenant_id=pr.tenant_id "
                + "and ps.workspace_id=pr.workspace_id and ps.promotion_id=pr.id and ps.sku_id=s.id) "
                + "or exists (select 1 from catalog_management.promotion_product pp where pp.tenant_id=pr.tenant_id "
                + "and pp.workspace_id=pr.workspace_id and pp.promotion_id=pr.id and pp.product_id=s.legacy_product_id) "
                + "or exists (select 1 from catalog_management.promotion_category pc where pc.tenant_id=pr.tenant_id "
                + "and pc.workspace_id=pr.workspace_id and pc.promotion_id=pr.id and pc.category_id=f.category_id))"
                + clientPredicate + " order by s.id,pr.id";
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(skuIds);
        parameters.add(Timestamp.from(asOf));
        parameters.add(Timestamp.from(asOf));
        if (customerAccountId != null) parameters.add(customerAccountId);
        Map<UUID, List<PromotionCandidate>> candidates = new HashMap<>();
        jdbc.query(sql, rs -> {
            while (rs.next()) {
                UUID skuId = rs.getObject("sku_id", UUID.class);
                PromotionCandidate candidate = new PromotionCandidate(rs.getObject("promotion_id", UUID.class),
                        rs.getString("name"), rs.getString("slug"),
                        Promotion.DiscountType.valueOf(rs.getString("discount_type")), rs.getBigDecimal("discount_value"),
                        rs.getString("currency"), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                        rs.getBigDecimal("minimum_quantity"), Promotion.StackingPolicy.valueOf(rs.getString("stacking_policy")),
                        PromotionStatus.valueOf(rs.getString("status")), rs.getInt("priority"), List.of(), List.of());
                candidates.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(candidate);
            }
            return null;
        }, parameters.toArray());
        if (candidates.isEmpty()) return Map.of();

        List<UUID> promotionIds = candidates.values().stream().flatMap(List::stream)
                .map(PromotionCandidate::id).distinct().toList();
        String promotionPlaceholders = placeholders(promotionIds.size());
        List<Object> scopedIds = new ArrayList<>(List.of(tenantId, workspaceId));
        scopedIds.addAll(promotionIds);
        Map<UUID, List<PromotionCandidate.PromotionRule>> rules = new HashMap<>();
        jdbc.query("select promotion_id,rule_type,rule_value from catalog_management.promotion_rule "
                        + "where tenant_id=? and workspace_id=? and promotion_id in (" + promotionPlaceholders + ") "
                        + "order by promotion_id,rule_type,rule_value",
                rs -> {
                    while (rs.next()) rules.computeIfAbsent(rs.getObject("promotion_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new PromotionCandidate.PromotionRule(rs.getString("rule_type"), rs.getString("rule_value")));
                    return null;
                }, scopedIds.toArray());
        Map<UUID, List<UUID>> accountIds = new HashMap<>();
        jdbc.query("select promotion_id,client_account_id from catalog_management.promotion_client_account "
                        + "where tenant_id=? and workspace_id=? and promotion_id in (" + promotionPlaceholders + ") "
                        + "order by promotion_id,client_account_id",
                rs -> {
                    while (rs.next()) accountIds.computeIfAbsent(rs.getObject("promotion_id", UUID.class), ignored -> new ArrayList<>())
                            .add(rs.getObject("client_account_id", UUID.class));
                    return null;
                }, scopedIds.toArray());

        Map<UUID, List<PromotionCandidate>> enriched = new HashMap<>();
        candidates.forEach((skuId, values) -> enriched.put(skuId, values.stream().map(candidate ->
                new PromotionCandidate(candidate.id(), candidate.name(), candidate.stableCode(), candidate.discountType(),
                        candidate.discountValue(), candidate.currency(), candidate.startsAt(), candidate.endsAt(),
                        candidate.minimumQuantity(), candidate.stackingPolicy(), candidate.status(), candidate.priority(),
                        accountIds.getOrDefault(candidate.id(), List.of()), rules.getOrDefault(candidate.id(), List.of())))
                .toList()));
        return Map.copyOf(enriched);
    }

    private String activeClientSegment(UUID tenantId, UUID workspaceId, UUID customerAccountId) {
        return jdbc.query("select segment from sales.client_account where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                        (rs, ignored) -> rs.getString(1), tenantId, workspaceId, customerAccountId)
                .stream().findFirst().orElse(null);
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(ignored -> "?").collect(Collectors.joining(","));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record BasePrice(BigDecimal amount, String currency) { }
    private record PriceRow(UUID skuId, BigDecimal amount, String currency) { }
    private record TermsRow(UUID id, UUID priceListId, String currency) { }
}
