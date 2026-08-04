package com.nexa.api.sales.infrastructure;

import com.nexa.api.catalogmanagement.application.service.CatalogQueryService;
import com.nexa.api.catalogmanagement.infrastructure.query.JdbcCatalogItemQueryAdapter;
import com.nexa.api.sales.infrastructure.seed.CatalogItemSnapshotPersistenceAdapter;
import com.nexa.api.sales.infrastructure.seed.SellableSkuSnapshotPersistenceAdapter;
import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.warehouse.infrastructure.persistence.CatalogProductAvailabilityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesSnapshotQueryBudgetIT extends PostgresIntegrationSupport {

    @Test
    void catalogAndSkuSnapshotLookupsRemainBatchBoundedForOneTenAndFiftyLines() {
        List<String> catalogIds = jdbc.query("select catalog_item_id from catalog_management.product where tenant_id=? and workspace_id=? order by catalog_item_id limit 50",
                (rs, row) -> rs.getString(1), UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        List<UUID> skuIds = jdbc.query("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? order by id limit 50",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        assertThat(catalogIds).hasSizeGreaterThanOrEqualTo(50);
        assertThat(skuIds).hasSizeGreaterThanOrEqualTo(50);

        List<Integer> catalogQueryCounts = new java.util.ArrayList<>();
        List<Integer> skuQueryCounts = new java.util.ArrayList<>();
        for (int lineCount : List.of(1, 10, 50)) {
            CountingJdbcTemplate catalogJdbc = new CountingJdbcTemplate(jdbc.getDataSource());
            var catalogUseCase = new CatalogQueryService(new JdbcCatalogItemQueryAdapter(catalogJdbc, new CatalogProductAvailabilityAdapter(catalogJdbc)));
            var catalogAdapter = new CatalogItemSnapshotPersistenceAdapter(catalogUseCase);
            assertThat(catalogAdapter.findActive(catalogIds.subList(0, lineCount), UUID.fromString(tenantId()), UUID.fromString(workspaceId())))
                    .hasSize(lineCount);
            catalogQueryCounts.add(catalogJdbc.queryCount());

            CountingJdbcTemplate skuJdbc = new CountingJdbcTemplate(jdbc.getDataSource());
            var skuAdapter = new SellableSkuSnapshotPersistenceAdapter(skuJdbc);
            assertThat(skuAdapter.findActive(skuIds.subList(0, lineCount), UUID.fromString(tenantId()), UUID.fromString(workspaceId())))
                    .hasSize(lineCount);
            skuQueryCounts.add(skuJdbc.queryCount());
        }

        assertThat(catalogQueryCounts).containsExactly(catalogQueryCounts.getFirst(), catalogQueryCounts.getFirst(), catalogQueryCounts.getFirst());
        assertThat(catalogQueryCounts.getFirst()).isLessThanOrEqualTo(4);
        assertThat(skuQueryCounts).containsExactly(1, 1, 1);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger queries = new AtomicInteger();

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.incrementAndGet();
            return super.query(sql, rowMapper, args);
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
            queries.incrementAndGet();
            return super.query(sql, extractor, args);
        }

        private int queryCount() {
            return queries.get();
        }
    }
}
