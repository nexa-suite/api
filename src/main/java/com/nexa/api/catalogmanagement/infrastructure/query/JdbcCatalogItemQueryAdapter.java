package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogSortField;
import com.nexa.api.catalogmanagement.application.model.SortDirection;
import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    public JdbcCatalogItemQueryAdapter(JdbcTemplate jdbc, ProductAvailabilityPort availability) {
        this.jdbc = jdbc;
        this.availability = availability;
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
            case ITEM_NAME -> "p.name";
            case BRAND_NAME -> "b.name";
            case CATEGORY_NAME -> "c.name";
            case UNIT_PRICE -> "coalesce(current_price.amount,0)";
        };
        String direction = criteria.sortDirection() == SortDirection.DESC ? " desc" : " asc";
        String sql = "select p.catalog_item_id,p.product_code,p.name,b.name,c.name,pp.presentation,p.description," +
                "p.storage_temperature,p.status,coalesce(current_price.amount,0),coalesce(current_price.currency,'PEN')," +
                "asset.asset_path,asset.file_name " + fromClause() + predicate +
                " order by " + order + direction + ",p.catalog_item_id asc limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(baseArgs);
        pageArgs.add(criteria.size());
        pageArgs.add((long) criteria.page() * criteria.size());
        List<Row> rows = jdbc.query(sql, (rs, row) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getBigDecimal(10), rs.getString(11), rs.getString(12), rs.getString(13)), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) " + fromClause() + predicate, Long.class, baseArgs.toArray());
        return new CatalogPage<>(rows.stream().map(row -> summary(scope, row)).toList(), criteria.page(), criteria.size(),
                total == null ? 0 : total, criteria.sortField(), criteria.sortDirection());
    }

    @Override
    public Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId id) {
        String predicate = " where p.tenant_id=? and p.workspace_id=? and p.catalog_item_id=? and p.status='ACTIVE'" +
                (scope.buyerView() ? " and pv.buyer_visible=true" : "");
        String sql = "select p.catalog_item_id,p.product_code,p.name,b.name,c.name,pp.presentation,p.description," +
                "p.storage_temperature,p.status,coalesce(current_price.amount,0),coalesce(current_price.currency,'PEN')," +
                "asset.asset_path,asset.file_name from catalog_management.product p " +
                "join catalog_management.brand b on b.tenant_id=p.tenant_id and b.workspace_id=p.workspace_id and b.id=p.brand_id " +
                "join catalog_management.category c on c.tenant_id=p.tenant_id and c.workspace_id=p.workspace_id and c.id=p.category_id " +
                "left join catalog_management.product_presentation pp on pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id and pp.product_id=p.id " +
                "left join catalog_management.product_visibility pv on pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id and pv.product_id=p.id " +
                "left join lateral (select amount,currency from catalog_management.product_price pr where pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id and pr.product_id=p.id and pr.cancelled_at is null and pr.valid_from<=current_timestamp and (pr.valid_until is null or pr.valid_until>current_timestamp) order by pr.valid_from desc,pr.id desc limit 1) current_price on true " +
                "left join lateral (select asset_path,file_name from catalog_management.product_asset_reference pa where pa.tenant_id=p.tenant_id and pa.workspace_id=p.workspace_id and pa.product_id=p.id order by pa.sort_order,pa.id limit 1) asset on true" + predicate;
        return jdbc.query(sql, (rs, row) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBigDecimal(10),
                rs.getString(11), rs.getString(12), rs.getString(13)), scope.tenantId(), scope.workspaceId(), id.value())
                .stream().findFirst().map(row -> detail(scope, row));
    }

    private CatalogItemSummary summary(CatalogScope scope, Row row) {
        Enrichment enrichment = enrich(scope, row.catalogItemId());
        return new CatalogItemSummary(row.catalogItemId(), row.productCode(), row.name(), row.brandName(), row.categoryName(),
                row.presentation(), row.amount(), row.currency(), row.temperature(), row.imagePath(), row.imageFileName(),
                row.status(), enrichment.status(), enrichment.nearExpiry(), enrichment.promotionLabel());
    }

    private CatalogItemDetail detail(CatalogScope scope, Row row) {
        Enrichment enrichment = enrich(scope, row.catalogItemId());
        return new CatalogItemDetail(row.catalogItemId(), row.productCode(), row.name(), row.brandName(), row.categoryName(),
                row.description(), row.presentation(), row.amount(), row.currency(), row.temperature(), row.imagePath(),
                row.imageFileName(), row.status(), enrichment.status(), enrichment.nearExpiry(), enrichment.promotionLabel());
    }

    private Enrichment enrich(CatalogScope scope, String catalogItemId) {
        ProductAvailabilityPort.Snapshot availabilitySnapshot = availability.find(scope, List.of(catalogItemId)).stream().findFirst()
                .orElse(new ProductAvailabilityPort.Snapshot(catalogItemId, "UNKNOWN", false, java.time.Instant.now()));
        String promotion = jdbc.queryForObject("select string_agg(distinct pr.name, ', ' order by pr.name) " +
                        "from catalog_management.product p " +
                        "join catalog_management.promotion pr on pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id " +
                        "left join catalog_management.promotion_product ppp on ppp.tenant_id=pr.tenant_id and ppp.workspace_id=pr.workspace_id and ppp.promotion_id=pr.id and ppp.product_id=p.id " +
                        "left join catalog_management.promotion_category ppc on ppc.tenant_id=pr.tenant_id and ppc.workspace_id=pr.workspace_id and ppc.promotion_id=pr.id and ppc.category_id=p.category_id " +
                        "where p.tenant_id=? and p.workspace_id=? and p.catalog_item_id=? and pr.status='ACTIVE' and (pr.starts_at is null or pr.starts_at<=current_timestamp) and (pr.ends_at is null or pr.ends_at>current_timestamp) and (ppp.product_id is not null or ppc.category_id is not null)",
                        String.class, scope.tenantId(), scope.workspaceId(), catalogItemId);
        return new Enrichment(availabilitySnapshot.status(), availabilitySnapshot.nearExpiry(), promotion);
    }

    private String fromClause() {
        return "from catalog_management.product p " +
                "join catalog_management.brand b on b.tenant_id=p.tenant_id and b.workspace_id=p.workspace_id and b.id=p.brand_id " +
                "join catalog_management.category c on c.tenant_id=p.tenant_id and c.workspace_id=p.workspace_id and c.id=p.category_id " +
                "left join catalog_management.product_presentation pp on pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id and pp.product_id=p.id " +
                "left join catalog_management.product_visibility pv on pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id and pv.product_id=p.id " +
                "left join lateral (select amount,currency from catalog_management.product_price pr where pr.tenant_id=p.tenant_id and pr.workspace_id=p.workspace_id and pr.product_id=p.id and pr.cancelled_at is null and pr.valid_from<=current_timestamp and (pr.valid_until is null or pr.valid_until>current_timestamp) order by pr.valid_from desc,pr.id desc limit 1) current_price on true " +
                "left join lateral (select asset_path,file_name from catalog_management.product_asset_reference pa where pa.tenant_id=p.tenant_id and pa.workspace_id=p.workspace_id and pa.product_id=p.id order by pa.sort_order,pa.id limit 1) asset on true";
    }

    private String predicate(CatalogScope scope, CatalogSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(" where p.tenant_id=? and p.workspace_id=? and p.status='ACTIVE'");
        if (scope.buyerView()) sql.append(" and pv.buyer_visible=true");
        if (criteria.query() != null && !criteria.query().isBlank()) sql.append(" and (lower(p.name) like lower(?) or lower(p.description) like lower(?) or lower(p.catalog_item_id) like lower(?))");
        if (criteria.brand() != null) sql.append(" and lower(b.name) like lower(?)");
        if (criteria.category() != null) sql.append(" and lower(c.name) like lower(?)");
        if (criteria.coldChainRequirement() != null) sql.append(" and p.storage_temperature=?");
        return sql.toString();
    }

    private List<Object> args(CatalogScope scope, CatalogSearchCriteria criteria) {
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        if (criteria.query() != null && !criteria.query().isBlank()) {
            String like = "%" + criteria.query() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (criteria.brand() != null) args.add("%" + criteria.brand() + "%");
        if (criteria.category() != null) args.add("%" + criteria.category() + "%");
        if (criteria.coldChainRequirement() != null) args.add(criteria.coldChainRequirement().name());
        return args;
    }

    private record Row(String catalogItemId, String productCode, String name, String brandName, String categoryName,
                       String presentation, String description, String temperature, String status, BigDecimal amount,
                       String currency, String imagePath, String imageFileName) { }
    private record Enrichment(String status, boolean nearExpiry, String promotionLabel) { }
}
