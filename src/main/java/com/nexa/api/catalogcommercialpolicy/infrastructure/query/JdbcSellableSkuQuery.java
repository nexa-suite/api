package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.AuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
public class JdbcSellableSkuQuery implements SellableSkuQuery {
    private static final String SELECT = "select s.id,s.family_id,f.family_code,s.sku_code,s.legacy_catalog_item_id,f.name,"
            + "s.presentation,s.unit_of_measure from catalog_management.sellable_sku s "
            + "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
            + "where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible and ";

    private final JdbcTemplate jdbc;
    private final AuthoritativeOfferQuery offers;

    @Autowired
    public JdbcSellableSkuQuery(JdbcTemplate jdbc, AuthoritativeOfferQuery offers) {
        this.jdbc = jdbc;
        this.offers = offers;
    }

    public JdbcSellableSkuQuery(JdbcTemplate jdbc) {
        this(jdbc, new JdbcAuthoritativeOfferQuery(jdbc));
    }

    @Override
    public Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID skuId) {
        return query(tenantId, workspaceId, null, "s.id=?", skuId, BigDecimal.ONE);
    }

    @Override
    public Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                       UUID customerAccountId, UUID skuId) {
        return query(tenantId, workspaceId, customerAccountId, "s.id=?", skuId, BigDecimal.ONE);
    }

    @Override
    public Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID customerAccountId,
                                                       UUID skuId, BigDecimal quantity) {
        return query(tenantId, workspaceId, customerAccountId, "s.id=?", skuId, quantity);
    }

    @Override
    public Optional<SellableSkuPolicy> findPhysicalValidationPolicy(UUID tenantId, UUID workspaceId, UUID skuId) {
        return jdbc.query("select id,status,visible,temperature_min,temperature_max from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, ignored) -> new SellableSkuPolicy(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getBoolean("visible"), rs.getBigDecimal("temperature_min"),
                        rs.getBigDecimal("temperature_max")), tenantId, workspaceId, skuId)
                .stream().findFirst();
    }

    @Override
    public Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, List<UUID> skuIds) {
        return findActive(tenantId, workspaceId, null, skuIds);
    }

    @Override
    public Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                        UUID customerAccountId, List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        Map<UUID, BigDecimal> quantities = skuIds.stream().filter(java.util.Objects::nonNull).distinct()
                .collect(Collectors.toUnmodifiableMap(id -> id, ignored -> BigDecimal.ONE));
        return findActive(tenantId, workspaceId, customerAccountId, quantities);
    }

    @Override
    public Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                        UUID customerAccountId, Map<UUID, BigDecimal> quantities) {
        if (quantities == null || quantities.isEmpty()) return Map.of();
        List<UUID> ids = quantities.keySet().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(ids);
        List<SkuRow> rows = jdbc.query(SELECT + "s.id in (" + placeholders + ")", (rs, ignored) -> row(rs), parameters.toArray());
        if (rows.isEmpty()) return Map.of();
        Instant asOf = Instant.now();
        Map<UUID, AuthoritativeOfferQuery.Offer> resolved = offers.resolve(tenantId, workspaceId,
                rows.stream().map(SkuRow::skuId).toList(), customerAccountId, null, null, quantities, asOf);
        Map<UUID, SellableSkuReference> result = new LinkedHashMap<>();
        for (SkuRow row : rows) {
            AuthoritativeOfferQuery.Offer offer = resolved.get(row.skuId());
            if (offer != null) result.put(row.skuId(), reference(row, offer));
        }
        return Map.copyOf(result);
    }

    @Override
    public Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(
            UUID tenantId, UUID workspaceId, String legacyCatalogItemId) {
        return query(tenantId, workspaceId, null, "s.legacy_catalog_item_id=?", legacyCatalogItemId, BigDecimal.ONE);
    }

    @Override
    public Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(UUID tenantId, UUID workspaceId,
                                                                            UUID customerAccountId,
                                                                            String legacyCatalogItemId) {
        return query(tenantId, workspaceId, customerAccountId, "s.legacy_catalog_item_id=?", legacyCatalogItemId, BigDecimal.ONE);
    }

    private Optional<SellableSkuReference> query(UUID tenantId, UUID workspaceId, UUID customerAccountId,
                                                  String selector, Object value, BigDecimal quantity) {
        List<SkuRow> rows = jdbc.query(SELECT + selector, (rs, ignored) -> row(rs), tenantId, workspaceId, value);
        if (rows.isEmpty()) return Optional.empty();
        SkuRow row = rows.getFirst();
        Map<UUID, AuthoritativeOfferQuery.Offer> resolved = offers.resolve(tenantId, workspaceId,
                List.of(row.skuId()), customerAccountId, null, null, Map.of(row.skuId(), quantity), Instant.now());
        AuthoritativeOfferQuery.Offer offer = resolved.get(row.skuId());
        return offer == null ? Optional.empty() : Optional.of(reference(row, offer));
    }

    private static SkuRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SkuRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
    }

    private static SellableSkuReference reference(SkuRow row, AuthoritativeOfferQuery.Offer offer) {
        return new SellableSkuReference(row.skuId(), row.familyId(), row.familyCode(), row.skuCode(),
                row.legacyCatalogItemId(), row.familyName(), row.presentation(), row.unitOfMeasure(),
                offer.effectivePrice(), offer.currency(), offer.basePrice(), offer.discountAmount(), offer.effectiveAt());
    }

    private record SkuRow(UUID skuId, UUID familyId, String familyCode, String skuCode,
                          String legacyCatalogItemId, String familyName, String presentation,
                          String unitOfMeasure) { }
}
