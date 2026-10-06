package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.bootstrap.local.LocalDevelopmentBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseObjectAccessIT extends PostgresIntegrationSupport {
    @Autowired
    private LocalDevelopmentBootstrap localDevelopmentBootstrap;

    @Test
    void localBootstrapGrantsOnlyItsWarehouseOperatorAndRerunsThroughGovernanceIdempotently() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID warehouse = jdbc.queryForObject("select id from warehouse.warehouse where tenant_id=? and workspace_id=? and code=?",
                UUID.class, tenant, workspace, "ICISA-COLD-01");
        UUID operatorMembership = UUID.fromString(membershipId(WAREHOUSE_EMAIL));
        UUID ownerMembership = UUID.fromString(membershipId(OWNER_EMAIL));

        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=? and status='ACTIVE'",
                Integer.class, tenant, workspace, warehouse, operatorMembership)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select changed_by_membership_id from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=?",
                UUID.class, tenant, workspace, warehouse, operatorMembership)).isEqualTo(ownerMembership);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id<>?",
                Integer.class, tenant, workspace, warehouse, operatorMembership)).isZero();

        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult listed = mockMvc.perform(get("/api/v1/warehouses").param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(java.util.stream.StreamSupport.stream(json(listed).get("items").spliterator(), false)
                .anyMatch(item -> "ICISA-COLD-01".equals(item.get("code").asText()))).isTrue();

        MvcResult lots = mockMvc.perform(get("/api/v1/inventory/lots").param("warehouseId", warehouse.toString())
                        .param("catalogItemId", "CAT-0002").param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(lots).get("items").size()).isGreaterThan(0);
        assertThat(json(lots).get("items").get(0).get("warehouseId").asText()).isEqualTo(warehouse.toString());

        String createdWarehouse = createWarehouse(warehouseToken, "WH-LOCAL-" + suffix());
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=?",
                Integer.class, tenant, workspace, UUID.fromString(createdWarehouse))).isZero();
        mockMvc.perform(get("/api/v1/warehouses/" + createdWarehouse)
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isNotFound());

        long operatorAuthorizationVersion = authorizationVersion(operatorMembership.toString());
        String grantChangedAt = jdbc.queryForObject("select changed_at::text from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=?",
                String.class, tenant, workspace, warehouse, operatorMembership);
        localDevelopmentBootstrap.seedWarehouseAfterCatalogReconciliation();

        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=? and status='ACTIVE'",
                Integer.class, tenant, workspace, warehouse, operatorMembership)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=?",
                Long.class, tenant, workspace, warehouse, operatorMembership)).isZero();
        assertThat(jdbc.queryForObject("select changed_at::text from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and membership_id=?",
                String.class, tenant, workspace, warehouse, operatorMembership)).isEqualTo(grantChangedAt);
        assertThat(authorizationVersion(operatorMembership.toString())).isEqualTo(operatorAuthorizationVersion);
        assertThat(jdbc.queryForObject("select count(*) from integration.change_event where tenant_id=? and workspace_id=? "
                        + "and aggregate_type='warehouse-access-grants' and aggregate_id=? "
                        + "and event_type='tenant.warehouse-access-grant.created'",
                Integer.class, tenant, workspace, warehouse)).isEqualTo(1);
    }

    @Test
    void buyerMembershipCannotReceiveAWorkforceWarehouseGrant() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouseId = createWarehouse(warehouse, "WH-BUYER-" + suffix());

        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(BUYER_EMAIL) + "\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where workspace_id=? and membership_id=? and warehouse_id=?",
                Integer.class, UUID.fromString(workspaceId()), UUID.fromString(membershipId(BUYER_EMAIL)),
                UUID.fromString(warehouseId))).isZero();
    }

    @Test
    void nonAdministratorCannotDistinguishExistingAndMissingWarehousesDuringGrantAdministration() throws Exception {
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String existingWarehouse = createWarehouse(warehouse, "WH-ADMIN-LOOKUP-" + suffix());
        String member = "Bearer " + warehouse;

        mockMvc.perform(get("/api/v1/warehouses/" + existingWarehouse + "/access-grants")
                        .header("Authorization", member))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/warehouses/" + UUID.randomUUID() + "/access-grants")
                        .header("Authorization", member))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveInternalMembershipCannotReceiveAWarehouseGrant() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouseId = createWarehouse(warehouse, "WH-INACTIVE-" + suffix());
        UUID target = UUID.fromString(membershipId(WAREHOUSE_EMAIL));
        jdbc.update("update tenant_management.workspace_membership set status='DISABLED' where id=?", target);
        try {
            mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                            .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + target + "\"}"))
                    .andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                            + "where workspace_id=? and membership_id=? and warehouse_id=?",
                    Integer.class, UUID.fromString(workspaceId()), target, UUID.fromString(warehouseId))).isZero();
        } finally {
            jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?", target);
        }
    }

    @Test
    void listCountsAndIdentifierResolutionOnlyIncludeGrantedWarehouses() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String visibleWarehouse = createWarehouse(warehouse, "WH-VISIBLE-" + suffix);
        String hiddenWarehouse = createWarehouse(warehouse, "WH-HIDDEN-" + suffix);
        grant(owner, visibleWarehouse, membershipId(WAREHOUSE_EMAIL));
        MvcResult hiddenGrant = grant(owner, hiddenWarehouse, membershipId(WAREHOUSE_EMAIL));
        String activeWarehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        String sharedBatch = "BATCH-" + suffix;
        String visibleZone = createZone(activeWarehouse, visibleWarehouse, "Z-VISIBLE-" + suffix);
        String hiddenZone = createZone(activeWarehouse, hiddenWarehouse, "Z-HIDDEN-" + suffix);
        receive(activeWarehouse, visibleWarehouse, visibleZone, sharedBatch, "visible-" + suffix);
        receive(activeWarehouse, hiddenWarehouse, hiddenZone, sharedBatch, "hidden-" + suffix);
        MvcResult beforeRevocation = mockMvc.perform(get("/api/v1/inventory-availability")
                        .param("catalogItemId", "CAT-0002").header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isOk()).andReturn();
        java.math.BigDecimal beforePhysical = json(beforeRevocation).get(0).get("physicalQuantity").decimalValue();
        mockMvc.perform(delete("/api/v1/warehouses/" + hiddenWarehouse + "/access-grants/" + membershipId(WAREHOUSE_EMAIL))
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", hiddenGrant.getResponse().getHeader("ETag")))
                .andExpect(status().isOk());
        activeWarehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        MvcResult list = mockMvc.perform(get("/api/v1/warehouses?page=0&size=100")
                        .header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isOk()).andReturn();
        var listJson = json(list);
        java.util.Set<String> listedWarehouses = new java.util.HashSet<>();
        listJson.get("items").forEach(item -> listedWarehouses.add(item.get("id").asText()));
        long totalWarehouses = listJson.get("total").asLong();
        for (int page = 1; page * 100L < totalWarehouses; page++) {
            MvcResult next = mockMvc.perform(get("/api/v1/warehouses")
                            .param("page", Integer.toString(page)).param("size", "100")
                            .header("Authorization", "Bearer " + activeWarehouse))
                    .andExpect(status().isOk()).andReturn();
            assertThat(json(next).get("total").asLong()).isEqualTo(totalWarehouses);
            json(next).get("items").forEach(item -> listedWarehouses.add(item.get("id").asText()));
        }
        assertThat(listedWarehouses).contains(visibleWarehouse).doesNotContain(hiddenWarehouse);
        assertThat((long) listedWarehouses.size()).isEqualTo(totalWarehouses);
        assertThat(listJson.get("total").asLong()).isEqualTo(jdbc.queryForObject(
                "select count(distinct warehouse_id) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and membership_id=? and status='ACTIVE'",
                Long.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(membershipId(WAREHOUSE_EMAIL))));

        MvcResult afterRevocation = mockMvc.perform(get("/api/v1/inventory-availability")
                        .param("catalogItemId", "CAT-0002").header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(afterRevocation).get(0).get("physicalQuantity").decimalValue())
                .isEqualByComparingTo(beforePhysical.subtract(new java.math.BigDecimal("2")));
        mockMvc.perform(get("/api/v1/warehouses/" + visibleWarehouse + "/inventory-availability")
                        .param("catalogItemId", "CAT-0002").header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].catalogItemId").value("CAT-0002"))
                .andExpect(jsonPath("$[0].physicalQuantity").value(2))
                .andExpect(jsonPath("$[0].sellableQuantity").value(2));
        mockMvc.perform(get("/api/v1/warehouses/" + hiddenWarehouse + "/inventory-availability")
                        .param("catalogItemId", "CAT-0002").header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/warehouses/" + visibleWarehouse + "/inventory-availability")
                        .header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/warehouses/" + hiddenWarehouse)
                        .header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inventory/lots/resolve")
                        .param("batchNumber", sharedBatch)
                        .header("Authorization", "Bearer " + activeWarehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.warehouseId").value(visibleWarehouse));
    }

    @Test
    void revokedGrantDeniesInboundIdempotencyReplay() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(warehouse, "WH-REVOKE-" + suffix);
        MvcResult grant = grant(owner, warehouseId, membershipId(WAREHOUSE_EMAIL));
        String grantEtag = grant.getResponse().getHeader("ETag");
        String activeWarehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String zoneId = createZone(activeWarehouse, warehouseId, "Z-REVOKE-" + suffix);
        String key = "warehouse-grant-replay-" + suffix;
        String receipt = "{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"B-REVOKE-" + suffix
                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":2,\"unit\":\"UNIT\"}";
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + activeWarehouse).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isCreated()).andReturn();
        String lotId = json(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/warehouses/" + warehouseId + "/access-grants/" + membershipId(WAREHOUSE_EMAIL))
                        .header("Authorization", "Bearer " + owner).header("If-Match", grantEtag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));

        String revokedWarehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + revokedWarehouse).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot where id=?",
                Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?",
                Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency "
                        + "where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isEqualTo(1);
    }

    @Test
    void concurrentFirstGrantIsIdempotentOrReturnsTypedConflictAndBumpsOnlyTargetAuthority() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouseId = createWarehouse(warehouse, "WH-CONCURRENT-" + suffix());
        String targetMembership = membershipId(WAREHOUSE_EMAIL);
        long targetVersionBefore = authorizationVersion(targetMembership);
        long actorVersionBefore = authorizationVersion(membershipId(OWNER_EMAIL));
        String command = "{\"membershipId\":\"" + targetMembership + "\"}";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> grantStatus(owner, warehouseId, command, ready, start));
            Future<Integer> second = executor.submit(() -> grantStatus(owner, warehouseId, command, ready, start));
            ready.await();
            start.countDown();
            List<Integer> statuses = List.of(first.get(), second.get());
            assertThat(statuses).allMatch(value -> value == 200 || value == 409);
            assertThat(statuses).contains(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and membership_id=? and warehouse_id=? and status='ACTIVE'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(targetMembership), UUID.fromString(warehouseId))).isEqualTo(1);
        assertThat(authorizationVersion(targetMembership)).isEqualTo(targetVersionBefore + 1);
        assertThat(authorizationVersion(membershipId(OWNER_EMAIL))).isEqualTo(actorVersionBefore);
        assertThat(jdbc.queryForObject("select count(*) from integration.change_event where tenant_id=? "
                        + "and workspace_id=? and aggregate_type='warehouse-access-grants' and aggregate_id=? "
                        + "and event_type='tenant.warehouse-access-grant.created'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(warehouseId))).isEqualTo(1);

        long targetVersionAfterGrant = authorizationVersion(targetMembership);
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content(command))
                .andExpect(status().isOk());
        assertThat(authorizationVersion(targetMembership)).isEqualTo(targetVersionAfterGrant);
    }

    private String createWarehouse(String owner, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Warehouse " + code + "\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("id").asText();
    }

    private MvcResult grant(String owner, String warehouseId, String memberId) throws Exception {
        long targetVersionBefore = authorizationVersion(memberId);
        String actorMembership = membershipId(OWNER_EMAIL);
        long actorVersionBefore = authorizationVersion(actorMembership);
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + memberId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(authorizationVersion(memberId)).isEqualTo(targetVersionBefore + 1);
        if (!memberId.equals(actorMembership)) {
            assertThat(authorizationVersion(actorMembership)).isEqualTo(actorVersionBefore);
        }
        return result;
    }

    private int grantStatus(String owner, String warehouseId, String body,
                            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private long authorizationVersion(String memberId) {
        return jdbc.queryForObject("select coalesce((select authorization_version "
                        + "from tenant_management.membership_authorization_state where membership_id=?),0)",
                Long.class, UUID.fromString(memberId));
    }

    private String createZone(String token, String warehouseId, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Zone " + code + "\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("id").asText();
    }

    private void receive(String token, String warehouseId, String zoneId, String batch, String key) throws Exception {
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"" + batch
                                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":2,\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
