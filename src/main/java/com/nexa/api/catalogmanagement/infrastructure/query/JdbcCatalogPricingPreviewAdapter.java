package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPreviewPort;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate.PromotionRule;
import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogPricingPreviewAdapter implements CatalogPricingPreviewPort {
    private final JdbcTemplate jdbc;

    public JdbcCatalogPricingPreviewAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<CatalogPricingPreviewModels.PriceContext> load(CatalogScope scope, List<UUID> productIds, Instant asOf) {
        if (productIds.isEmpty()) return List.of();
        String placeholders = productIds.stream().map(value -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(asOf), Timestamp.from(asOf), scope.tenantId(), scope.workspaceId()));
        args.addAll(productIds);
        Map<UUID, PriceRow> prices = new HashMap<>();
        jdbc.query("select distinct on (p.id) p.id,coalesce(pr.amount,0),coalesce(pr.currency,'PEN') " +
                "from catalog_management.product p left join catalog_management.product_price pr on pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id and pr.product_id=p.id and pr.cancelled_at is null and pr.valid_from<=? and (pr.valid_until is null or pr.valid_until>?) " +
                "where p.tenant_id=? and p.workspace_id=? and p.id in (" + placeholders + ") and p.status<>'ARCHIVED' order by p.id,pr.valid_from desc nulls last,pr.id desc nulls last",
                (rs, row) -> { prices.put(rs.getObject(1, UUID.class), new PriceRow(rs.getBigDecimal(2), rs.getString(3))); return null; },
                args.toArray());

        List<Object> promotionArgs = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId(), Timestamp.from(asOf), Timestamp.from(asOf)));
        promotionArgs.addAll(productIds);
        if (scope.clientAccountId() != null) promotionArgs.add(scope.clientAccountId());
        String clientPredicate = scope.clientAccountId() == null
                ? " and not exists (select 1 from catalog_management.promotion_client_account pca0 where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id)"
                : " and (not exists (select 1 from catalog_management.promotion_client_account pca0 where pca0.tenant_id=pr.tenant_id and pca0.workspace_id=pr.workspace_id and pca0.promotion_id=pr.id) or exists (select 1 from catalog_management.promotion_client_account pca1 where pca1.tenant_id=pr.tenant_id and pca1.workspace_id=pr.workspace_id and pca1.promotion_id=pr.id and pca1.client_account_id=?))";
        String promotionSql = "select p.id,pr.id,pr.name,pr.slug,pr.discount_type,pr.discount_value,pr.currency,pr.starts_at,pr.ends_at,pr.minimum_quantity,pr.stacking_policy,pr.status,pr.priority " +
                "from catalog_management.product p join catalog_management.promotion pr on pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id " +
                "where p.tenant_id=? and p.workspace_id=? and (pr.starts_at is null or pr.starts_at<=?) and (pr.ends_at is null or pr.ends_at>?) and pr.status in ('ACTIVE','SCHEDULED') and p.id in (" + placeholders + ") " +
                "and (exists (select 1 from catalog_management.promotion_product ppp where ppp.tenant_id=pr.tenant_id and ppp.workspace_id=pr.workspace_id and ppp.promotion_id=pr.id and ppp.product_id=p.id) or exists (select 1 from catalog_management.promotion_category ppc where ppc.tenant_id=pr.tenant_id and ppc.workspace_id=pr.workspace_id and ppc.promotion_id=pr.id and ppc.category_id=p.category_id))" + clientPredicate + " order by p.id,pr.priority desc,pr.starts_at nulls last,pr.slug,pr.id";
        Map<UUID, List<PromotionCandidate>> candidates = new HashMap<>();
        jdbc.query(promotionSql, (rs, row) -> {
            UUID productId = rs.getObject(1, UUID.class);
            candidates.computeIfAbsent(productId, ignored -> new ArrayList<>()).add(new PromotionCandidate(rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), Promotion.DiscountType.valueOf(rs.getString(5)), rs.getBigDecimal(6), rs.getString(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)),
                    rs.getBigDecimal(10), Promotion.StackingPolicy.valueOf(rs.getString(11)), PromotionStatus.valueOf(rs.getString(12)), rs.getInt(13), List.of(), List.of()));
            return null;
        }, promotionArgs.toArray());
        List<UUID> promotionIds = candidates.values().stream().flatMap(List::stream).map(PromotionCandidate::id).distinct().toList();
        if (!promotionIds.isEmpty()) {
            String promotionPlaceholders = promotionIds.stream().map(value -> "?").collect(Collectors.joining(","));
            List<Object> ruleArgs = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
            ruleArgs.addAll(promotionIds);
            Map<UUID, List<PromotionRule>> rules = new HashMap<>();
            jdbc.query("select promotion_id,rule_type,rule_value from catalog_management.promotion_rule where tenant_id=? and workspace_id=? and promotion_id in (" + promotionPlaceholders + ") order by promotion_id,rule_type,rule_value",
                    (rs, row) -> { rules.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(new PromotionRule(rs.getString(2), rs.getString(3))); return null; }, ruleArgs.toArray());
            candidates.replaceAll((productId, values) -> values.stream().map(value -> new PromotionCandidate(value.id(), value.name(), value.stableCode(), value.discountType(), value.discountValue(), value.currency(), value.startsAt(), value.endsAt(), value.minimumQuantity(), value.stackingPolicy(), value.status(), value.priority(), value.clientAccountIds(), rules.getOrDefault(value.id(), List.of()))).toList());
        }
        return productIds.stream().filter(prices::containsKey).map(id -> new CatalogPricingPreviewModels.PriceContext(id, prices.get(id).amount(), prices.get(id).currency(), candidates.getOrDefault(id, List.of()))).toList();
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record PriceRow(BigDecimal amount, String currency) { }
}
