package com.nexa.api.catalogmanagement.infrastructure;

import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogSortField;
import com.nexa.api.catalogmanagement.application.model.SortDirection;
import com.nexa.api.catalogmanagement.infrastructure.query.JdbcCatalogItemQueryAdapter;
import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.warehouse.infrastructure.persistence.CatalogProductAvailabilityAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CatalogWaveDConcurrencyIT extends PostgresIntegrationSupport {
    private static final JsonMapper JSON = JsonMapper.shared();

    private UUID priceProduct;
    private UUID promotion;
    private final List<CommandKey> commandKeys = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        commandKeys.forEach(key -> jdbc.update(
                "delete from catalog_management.command_idempotency where operation=? and idempotency_key=?",
                key.operation(), key.key()));
        if (promotion != null) {
            jdbc.update("delete from catalog_management.promotion_product where promotion_id=?", promotion);
            jdbc.update("delete from catalog_management.promotion_category where promotion_id=?", promotion);
            jdbc.update("delete from catalog_management.promotion_client_account where promotion_id=?", promotion);
            jdbc.update("delete from catalog_management.promotion_rule where promotion_id=?", promotion);
            jdbc.update("delete from catalog_management.promotion where id=?", promotion);
        }
        if (priceProduct != null) {
            jdbc.update("delete from catalog_management.product_price where product_id=?", priceProduct);
            jdbc.update("delete from catalog_management.sku_price where sku_id=?", priceProduct);
            UUID familyId = jdbc.query("select family_id from catalog_management.sellable_sku where id=?", (rs, row) -> rs.getObject(1, UUID.class), priceProduct).stream().findFirst().orElse(null);
            jdbc.update("delete from catalog_management.sellable_sku where id=?", priceProduct);
            if (familyId != null) jdbc.update("delete from catalog_management.product_family where id=?", familyId);
            jdbc.update("delete from catalog_management.product_presentation where product_id=?", priceProduct);
            jdbc.update("delete from catalog_management.product_visibility where product_id=?", priceProduct);
            jdbc.update("delete from catalog_management.product_asset_reference where product_id=?", priceProduct);
            jdbc.update("delete from catalog_management.product where id=?", priceProduct);
        }
    }

    @Test
    void concurrentOverlappingPriceCreationReturnsOneCreatedAndOneConflict() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        priceProduct = createPriceProduct();
        String currency = configuredPriceCurrency();
        Instant validFrom = Instant.now().plusSeconds(120);
        Instant validUntil = validFrom.plusSeconds(3_600);
        String body = "{\"amount\":12.30,\"currency\":\"" + currency + "\",\"validFrom\":\"" + validFrom
                + "\",\"validUntil\":\"" + validUntil + "\",\"sourceCode\":\"WAVE-D\",\"sourceDescription\":\"Wave D\"}";
        String firstKey = remember("price:create", "wave-d-price-a-" + UUID.randomUUID());
        String secondKey = remember("price:create", "wave-d-price-b-" + UUID.randomUUID());
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<HttpResult> results = runConcurrently(
                () -> priceAttempt(barrier, owner, firstKey, body),
                () -> priceAttempt(barrier, owner, secondKey, body));

        assertThat(results).allSatisfy(result -> assertThat(result.failure())
                .as("concurrent price request failure")
                .isNull());
        assertThat(results.stream().map(HttpResult::status).toList()).containsExactlyInAnyOrder(201, 409);
        HttpResult conflict = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
        assertThat(JSON.readTree(conflict.body()).get("code").asText()).isEqualTo("CATALOG_PRICE_OVERLAP");
        assertThat(jdbc.queryForObject(
                "select count(*) from catalog_management.product_price where product_id=? and cancelled_at is null",
                Integer.class, priceProduct)).isEqualTo(1);
    }

    @Test
    void concurrentPromotionActivationWithSameIfMatchReturnsOneWinnerAndOneControlledConflict() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String suffix = UUID.randomUUID().toString();
        String key = remember("promotion:create", "wave-d-promotion-" + suffix);
        String body = "{\"slug\":\"wave-d-" + suffix.substring(0, 8)
                + "\",\"name\":\"Wave D promotion\",\"description\":\"Concurrency evidence\","
                + "\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"currency\":null,"
                + "\"startsAt\":null,\"endsAt\":null,\"minimumQuantity\":1,\"stackingPolicy\":\"EXCLUSIVE\","
                + "\"productIds\":[],\"categoryIds\":[],\"clientAccountIds\":[],\"rules\":[],\"priority\":0}";
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/promotions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdJson = JSON.readTree(created.getResponse().getContentAsString());
        promotion = UUID.fromString(createdJson.get("id").asText());
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isEqualTo("\"0\"");

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<HttpResult> results = runConcurrently(
                () -> activationAttempt(barrier, owner, etag),
                () -> activationAttempt(barrier, owner, etag));

        assertThat(results).allSatisfy(result -> assertThat(result.failure())
                .as("concurrent promotion transition failure")
                .isNull());
        assertThat(results.stream().map(HttpResult::status).toList()).containsExactlyInAnyOrder(200, 409);
        HttpResult conflict = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
        assertThat(JSON.readTree(conflict.body()).get("code").asText()).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select status from catalog_management.promotion where id=?", String.class, promotion))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select version from catalog_management.promotion where id=?", Long.class, promotion))
                .isEqualTo(1L);
    }

    @Test
    void persistentCatalogSearchUsesFourJdbcQueriesForPageSizesOneTenAndTwentyFive() {
        DataSource dataSource = jdbc.getDataSource();
        assertThat(dataSource).isNotNull();
        CatalogScope scope = new CatalogScope(UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        for (int pageSize : List.of(1, 10, 25)) {
            CountingJdbcTemplate countedJdbc = new CountingJdbcTemplate(dataSource);
            JdbcCatalogItemQueryAdapter adapter = new JdbcCatalogItemQueryAdapter(
                    countedJdbc, new CatalogProductAvailabilityAdapter(countedJdbc));
            var page = adapter.search(scope, new CatalogSearchCriteria("", null, null, null, 0, pageSize,
                    CatalogSortField.ITEM_NAME, SortDirection.ASC));

            assertThat(page.items()).hasSize(pageSize);
            assertThat(countedJdbc.queryCount())
                    .as("JdbcTemplate query count for page size %s", pageSize)
                    .isEqualTo(4);
        }
    }

    private UUID createPriceProduct() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID category = jdbc.queryForObject(
                "select id from catalog_management.category where tenant_id=? and workspace_id=? limit 1",
                UUID.class, tenant, workspace);
        UUID brand = jdbc.queryForObject(
                "select id from catalog_management.brand where tenant_id=? and workspace_id=? limit 1",
                UUID.class, tenant, workspace);
        UUID product = UUID.randomUUID();
        Instant now = Instant.now();
        String suffix = product.toString();
        jdbc.update("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?)",
                product, tenant, workspace, "WAVE-D-" + suffix, "WAVE-D-" + suffix, "wave-d-" + suffix,
                "Wave D price product", "Concurrency test product", category, brand, "REFRIGERATED",
                Timestamp.from(now), Timestamp.from(now));
        return product;
    }

    private String configuredPriceCurrency() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        return jdbc.query("select currency from tenant_management.regional_settings where tenant_id=?",
                        (rs, row) -> rs.getString(1), tenant).stream()
                .findFirst()
                .orElseGet(() -> jdbc.query("select currency from catalog_management.product_price where tenant_id=? and workspace_id=? and cancelled_at is null order by valid_from limit 1",
                                (rs, row) -> rs.getString(1), tenant, workspace).stream()
                        .findFirst()
                        .orElse("PEN"))
                .strip();
    }

    private HttpResult priceAttempt(CyclicBarrier barrier, String owner, String key, String body) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(post("/api/v1/catalog/products/" + priceProduct + "/prices")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            return result(result);
        } catch (Throwable failure) {
            return new HttpResult(-1, "", failure);
        }
    }

    private HttpResult activationAttempt(CyclicBarrier barrier, String owner, String etag) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(post("/api/v1/catalog/promotions/" + promotion + "/activations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                            .header(HttpHeaders.IF_MATCH, etag))
                    .andReturn();
            return result(result);
        } catch (Throwable failure) {
            return new HttpResult(-1, "", failure);
        }
    }

    private static HttpResult result(MvcResult result) throws Exception {
        return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString(), null);
    }

    private static List<HttpResult> runConcurrently(Callable<HttpResult> first, Callable<HttpResult> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResult>> futures = executor.invokeAll(List.of(first, second));
            return futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    return new HttpResult(-1, "", failure);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private String remember(String operation, String key) {
        commandKeys.add(new CommandKey(operation, key));
        return key;
    }

    private record CommandKey(String operation, String key) { }

    private record HttpResult(int status, String body, Throwable failure) { }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger queryCount = new AtomicInteger();

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount.incrementAndGet();
            return super.query(sql, rowMapper, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            queryCount.incrementAndGet();
            return super.queryForObject(sql, requiredType, args);
        }

        private int queryCount() {
            return queryCount.get();
        }
    }
}
