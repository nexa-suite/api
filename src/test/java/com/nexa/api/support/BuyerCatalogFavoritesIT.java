package com.nexa.api.support;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.tenant-business.catalog-favorites.enabled=true")
class BuyerCatalogFavoritesIT extends PostgresIntegrationSupport {
    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void buyerCanSaveAndRemoveAVisibleOfferAndCleanUpItsHiddenSku() throws Exception {
        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        JsonNode catalogItem = firstBuyerCatalogItem(buyerToken);
        UUID skuId = UUID.fromString(catalogItem.path("sellableSkuId").asText());
        String favoritePath = "/api/v1/buyer/favorites/" + skuId;

        var firstSave = mockMvc.perform(put(favoritePath).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellableSkuId").value(skuId.toString()))
                .andExpect(jsonPath("$.currentOffer.sellableSkuId").value(skuId.toString()))
                .andExpect(jsonPath("$.currentOffer.currentPrice.amount").exists())
                .andReturn();
        String favoritedAt = JSON.readTree(firstSave.getResponse().getContentAsString()).path("favoritedAt").asText();

        mockMvc.perform(put(favoritePath).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favoritedAt").value(favoritedAt));

        mockMvc.perform(get("/api/v1/buyer/favorites").param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sellableSkuId").value(skuId.toString()));

        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        try {
            tenantJdbc().update("update catalog_management.sellable_sku set visible=false "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    tenantId, workspaceId, skuId);

            mockMvc.perform(get("/api/v1/buyer/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].sellableSkuId").value(skuId.toString()))
                    .andExpect(jsonPath("$.items[0].currentOffer").value(org.hamcrest.Matchers.nullValue()));

            mockMvc.perform(delete(favoritePath).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete(favoritePath).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/buyer/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0))
                    .andExpect(jsonPath("$.items").isEmpty());
        } finally {
            tenantJdbc().update("update catalog_management.sellable_sku set visible=true "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    tenantId, workspaceId, skuId);
        }
    }

    @Test
    void concurrentAddAndRemoveForOneFavoriteWaitOnTheSameTransactionLock() throws Exception {
        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        UUID buyerMembershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID skuId = UUID.fromString(firstBuyerCatalogItem(buyerToken).path("sellableSkuId").asText());
        String path = "/api/v1/buyer/favorites/" + skuId;
        String lockKey = favoriteLockKey(tenantId, workspaceId, buyerMembershipId, skuId);
        TenantBusinessDatabaseCredentials credentials = TenantBusinessIntegrationFixture.credentials(
                TenantBusinessIntegrationFixture.credentialReference(tenantId));

        assertFavoriteMutationWaitsForLock(credentials, lockKey,
                () -> asyncFavoriteStatus(true, path, buyerToken), 200);
        assertFavoriteMutationWaitsForLock(credentials, lockKey,
                () -> asyncFavoriteStatus(false, path, buyerToken), 204);
    }

    @Test
    void tenantRuntimeRlsScopesFavoriteRowsByMembershipAndResetsMembershipScopeAtTransactionEnd() throws Exception {
        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        UUID buyerMembershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        // V3 allows one Buyer membership per Client Account. Use another real
        // Workspace membership and a separately seeded account for this RLS subject.
        UUID otherMembershipId = UUID.fromString(membershipId(SALES_EMAIL));
        UUID otherClientAccountId = UUID.randomUUID();
        UUID skuId = UUID.fromString(firstBuyerCatalogItem(buyerToken).path("sellableSkuId").asText());
        mockMvc.perform(put("/api/v1/buyer/favorites/" + skuId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        try {
            seedIsolatedBuyerAccount(otherClientAccountId, tenantId, workspaceId);
            tenantJdbc().update("insert into sales.client_account_membership "
                            + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
                            + "values (?,?,?,?,?)",
                    otherClientAccountId, otherMembershipId, tenantId, workspaceId, Timestamp.from(Instant.now()));

            TenantBusinessDatabaseCredentials credentials = TenantBusinessIntegrationFixture.credentials(
                    TenantBusinessIntegrationFixture.credentialReference(tenantId));
            try (Connection connection = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
                    credentials.password())) {
                String membershipBefore = currentMembershipScope(connection);
                connection.setAutoCommit(false);
                setLocal(connection, "app.current_tenant_id", tenantId.toString());
                setLocal(connection, "app.current_workspace_id", workspaceId.toString());
                setLocal(connection, "app.current_membership_id", buyerMembershipId.toString());
                assertThat(favoriteCount(connection)).isEqualTo(1);

                setLocal(connection, "app.current_membership_id", otherMembershipId.toString());
                assertThat(favoriteCount(connection)).isZero();
                insertFavorite(connection, tenantId, workspaceId, otherMembershipId, skuId);
                assertThat(favoriteCount(connection)).isEqualTo(1);
                connection.commit();
                assertThat(currentMembershipScope(connection)).isEqualTo(membershipBefore);

                connection.setAutoCommit(false);
                setLocal(connection, "app.current_tenant_id", tenantId.toString());
                setLocal(connection, "app.current_workspace_id", workspaceId.toString());
                setLocal(connection, "app.current_membership_id", otherMembershipId.toString());
                assertThatThrownBy(() -> insertFavorite(connection, tenantId, workspaceId, buyerMembershipId, skuId))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("row-level security");
                connection.rollback();
                assertThat(currentMembershipScope(connection)).isEqualTo(membershipBefore);

                connection.setAutoCommit(false);
                setLocal(connection, "app.current_tenant_id", tenantId.toString());
                setLocal(connection, "app.current_workspace_id", workspaceId.toString());
                assertThat(favoriteCount(connection)).isZero();
                assertThatThrownBy(() -> insertFavorite(connection, tenantId, workspaceId, buyerMembershipId, skuId))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("row-level security");
                connection.rollback();
                assertThat(currentMembershipScope(connection)).isEqualTo(membershipBefore);
            }
        } finally {
            tenantJdbc().update("delete from sales.buyer_catalog_favorite where tenant_id=? and workspace_id=? "
                            + "and buyer_membership_id=? and sellable_sku_id=?",
                    tenantId, workspaceId, buyerMembershipId, skuId);
            tenantJdbc().update("delete from sales.client_account_membership where tenant_id=? and workspace_id=? "
                            + "and client_account_id=? and workspace_membership_id=?",
                    tenantId, workspaceId, otherClientAccountId, otherMembershipId);
            tenantJdbc().update("delete from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                    tenantId, workspaceId, otherClientAccountId);
        }
    }

    private void seedIsolatedBuyerAccount(UUID clientAccountId, UUID tenantId, UUID workspaceId) {
        String suffix = clientAccountId.toString().replace("-", "").substring(0, 12);
        String ruc = "20" + String.format(Locale.ROOT, "%09d",
                Math.floorMod(clientAccountId.getMostSignificantBits(), 1_000_000_000L));
        Timestamp now = Timestamp.from(Instant.now());
        tenantJdbc().update("insert into sales.client_account "
                        + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                        + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                clientAccountId, tenantId, workspaceId, "FAV-" + suffix, "Favorites RLS Fixture " + suffix,
                "Favorites RLS Fixture " + suffix, "PE", "RUC", ruc, "FAV-" + suffix,
                "Fixture Contact", "favorites-" + suffix + "@example.test", "+51999999999",
                "Fixture delivery profile", "NET_30", "ACTIVE", now, now);
    }

    private JsonNode firstBuyerCatalogItem(String buyerToken) throws Exception {
        var catalog = mockMvc.perform(get("/api/v1/catalog-items").param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = JSON.readTree(catalog.getResponse().getContentAsString()).path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isGreaterThan(0);
        return items.get(0);
    }

    private void assertFavoriteMutationWaitsForLock(TenantBusinessDatabaseCredentials credentials, String lockKey,
            Supplier<CompletableFuture<Integer>> startRequest, int expectedStatus) throws Exception {
        boolean waiting;
        CompletableFuture<Integer> requestStatus;
        try (Connection blocker = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
                credentials.password())) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, lockKey);
                statement.execute();
            }
            requestStatus = startRequest.get();
            waiting = awaitAdvisoryLockWait(credentials, requestStatus);
            blocker.rollback();
        }
        assertThat(waiting).as("favorite mutation waits for its same-key advisory lock").isTrue();
        assertThat(requestStatus.get(10, TimeUnit.SECONDS)).isEqualTo(expectedStatus);
    }

    private boolean awaitAdvisoryLockWait(TenantBusinessDatabaseCredentials credentials,
            CompletableFuture<Integer> requestStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (requestStatus.isDone()) return false;
            try (Connection probe = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
                    credentials.password()); Statement statement = probe.createStatement();
                    ResultSet result = statement.executeQuery("select exists (select 1 from pg_locks "
                            + "where locktype='advisory' and not granted)")) {
                if (result.next() && result.getBoolean(1)) return true;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private CompletableFuture<Integer> asyncFavoriteStatus(boolean add, String path, String buyerToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var request = (add ? put(path) : delete(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken);
                return mockMvc.perform(request).andReturn().getResponse().getStatus();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String favoriteLockKey(UUID tenantId, UUID workspaceId, UUID membershipId, UUID skuId) {
        return tenantId + ":" + workspaceId + ":buyer-catalog-favorite:" + membershipId + ":" + skuId;
    }

    @Test
    void openApiPublishesTheFeatureGatedBuyerFavoriteContract() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode document = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(document.path("paths").has("/api/v1/buyer/favorites")).isTrue();
        assertThat(document.path("paths").path("/api/v1/buyer/favorites").has("get")).isTrue();
        assertThat(document.path("paths").has("/api/v1/buyer/favorites/{sellableSkuId}")).isTrue();
        assertThat(document.path("paths").path("/api/v1/buyer/favorites/{sellableSkuId}").has("put")).isTrue();
        assertThat(document.path("paths").path("/api/v1/buyer/favorites/{sellableSkuId}").has("delete")).isTrue();
        JsonNode currentOffer = document.path("components").path("schemas").path("BuyerCatalogFavoriteResponse")
                .path("properties").path("currentOffer");
        assertThat(currentOffer.path("anyOf").isArray()).isTrue();
        assertThat(currentOffer.path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#/components/schemas/CurrentCatalogOfferResponse");
        assertThat(currentOffer.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
        assertThat(currentOffer.has("type")).isFalse();
        assertThat(currentOffer.has("$ref")).isFalse();

        assertProblemResponses(document, "/api/v1/buyer/favorites", "get");
        assertProblemResponses(document, "/api/v1/buyer/favorites/{sellableSkuId}", "put");
        assertProblemResponses(document, "/api/v1/buyer/favorites/{sellableSkuId}", "delete");
        if (Boolean.getBoolean("nexa.openapi.write-snapshot")) {
            Files.writeString(Path.of("docs/openapi/openapi.json"), document.toString() + System.lineSeparator());
        }
    }

    private static void assertProblemResponses(JsonNode document, String path, String method) {
        JsonNode responses = document.path("paths").path(path).path(method).path("responses");
        responses.properties().forEach(entry -> {
            if (!entry.getKey().startsWith("4") && !entry.getKey().startsWith("5")) return;
            JsonNode content = entry.getValue().path("content");
            assertThat(content.has("*/*")).as("%s %s response %s must not publish a success schema", method, path,
                    entry.getKey()).isFalse();
            assertThat(content.path("application/problem+json").path("schema").path("$ref").asText())
                    .as("%s %s response %s must use NexaProblemDetail", method, path, entry.getKey())
                    .isEqualTo("#/components/schemas/NexaProblemDetail");
        });
    }

    private static void setLocal(Connection connection, String setting, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, setting);
            statement.setString(2, value);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(value);
            }
        }
    }

    private static long favoriteCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from sales.buyer_catalog_favorite")) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void insertFavorite(Connection connection, UUID tenantId, UUID workspaceId,
            UUID membershipId, UUID skuId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("insert into sales.buyer_catalog_favorite "
                + "(tenant_id,workspace_id,buyer_membership_id,sellable_sku_id) values (?,?,?,?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, workspaceId);
            statement.setObject(3, membershipId);
            statement.setObject(4, skuId);
            statement.executeUpdate();
        }
    }

    private static String currentMembershipScope(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select coalesce(current_setting('app.current_membership_id', true), '')")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
