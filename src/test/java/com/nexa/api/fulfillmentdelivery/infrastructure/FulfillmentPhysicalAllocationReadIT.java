package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class FulfillmentPhysicalAllocationReadIT extends NexaWorkflowIntegrationSupport {
    @Test
    void pickingWorkListReturnsOnlyCurrentAllocatedWorkForFulfillmentReaders() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult listed = mockMvc.perform(get("/api/v1/fulfillments")
                        .param("page", "0").param("size", "25")
                        .header("Authorization", "Bearer " + fixture.warehouseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.asOf").isNotEmpty())
                .andReturn();
        var page = json(listed);
        var item = java.util.stream.StreamSupport.stream(page.get("items").spliterator(), false)
                .filter(value -> fixture.fulfillmentId().equals(value.get("fulfillmentId").asText()))
                .findFirst().orElseThrow();
        assertThat(item.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(item.get("physicalAllocationId").asText()).isEqualTo(fixture.allocationId());
        assertThat(item.get("allocationVersion").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(item.get("lineCount").asInt()).isEqualTo(1);

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/fulfillments")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedWarehouseReadsCurrentScopedAllocationAndBuyerCannotReadIt() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult allocation = readAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn();

        var body = json(allocation);
        assertThat(body.get("allocationId").asText()).isEqualTo(fixture.allocationId());
        assertThat(body.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(allocation.getResponse().getHeader("ETag")).isEqualTo("\"" + body.get("version").asLong() + "\"");
        assertThat(body.get("asOf").asText()).isNotBlank();
        assertThat(body.get("lines").size()).isEqualTo(1);
        var line = body.get("lines").get(0);
        assertThat(line.get("physicalAllocationLineId").asText()).isNotBlank();
        assertThat(line.get("skuId").asText()).isNotBlank();
        assertThat(line.get("catalogItemId").asText()).isEqualTo("CAT-0002");
        assertThat(line.get("warehouseId").asText()).isNotBlank();
        assertThat(line.get("lotId").asText()).isNotBlank();
        assertThat(line.get("quantity").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(line.get("releasedQuantity").decimalValue()).isZero();
        assertThat(line.get("consumedQuantity").decimalValue()).isZero();
        assertThat(line.get("remainingQuantity").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(line.get("unit").asText()).isEqualTo("UNIT");
        String sourceExpiration = jdbc.queryForObject(
                "select expiration_date::text from warehouse.physical_allocation_line where tenant_id=? and workspace_id=? and id=?",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(line.get("physicalAllocationLineId").asText()));
        assertThat(line.get("expirationDate").asText()).isEqualTo(sourceExpiration);

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        readAllocation(fixture, buyer).andExpect(status().isForbidden());
    }

    @Test
    void anotherActiveWarehouseGrantDoesNotExposeAllocationFromWarehouseA() throws Exception {
        Fixture fixture = createFulfillment();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouseB = createWarehouse(fixture.warehouseToken(), "WH-READ-B-" + suffix());
        MvcResult grantA = grant(owner, fixture.warehouseId(), membershipId(WAREHOUSE_EMAIL));
        grant(owner, warehouseB, membershipId(WAREHOUSE_EMAIL));
        String authorizedWarehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        readAllocation(fixture, authorizedWarehouse).andExpect(status().isOk());
        MvcResult beforeList = mockMvc.perform(get("/api/v1/fulfillments")
                        .param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + authorizedWarehouse))
                .andExpect(status().isOk()).andReturn();
        var beforePage = json(beforeList);
        assertThat(java.util.stream.StreamSupport.stream(beforePage.get("items").spliterator(), false)
                .anyMatch(value -> fixture.fulfillmentId().equals(value.get("fulfillmentId").asText())))
                .isTrue();
        mockMvc.perform(delete("/api/v1/warehouses/" + fixture.warehouseId() + "/access-grants/"
                                + membershipId(WAREHOUSE_EMAIL))
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", grantA.getResponse().getHeader("ETag")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(jdbc.queryForObject("select count(*) from tenant_management.warehouse_access_grant "
                        + "where tenant_id=? and workspace_id=? and membership_id=? and warehouse_id=? and status='ACTIVE'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(membershipId(WAREHOUSE_EMAIL)), UUID.fromString(warehouseB))).isEqualTo(1);
        String warehouseWithOnlyB = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        readAllocation(fixture, warehouseWithOnlyB)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
        MvcResult afterList = mockMvc.perform(get("/api/v1/fulfillments")
                        .param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + warehouseWithOnlyB))
                .andExpect(status().isOk()).andReturn();
        var afterPage = json(afterList);
        assertThat(java.util.stream.StreamSupport.stream(afterPage.get("items").spliterator(), false)
                .noneMatch(value -> fixture.fulfillmentId().equals(value.get("fulfillmentId").asText())))
                .isTrue();
        assertThat(afterPage.get("totalItems").asLong())
                .isLessThan(beforePage.get("totalItems").asLong());
    }

    @Test
    void fulfillmentFromAnotherTenantReturnsScopedNotFound() throws Exception {
        Fixture fixture = createFulfillment();
        String foreignWorkspace = createWorkspaceForWarehouse(suffix());
        String foreignWarehouseToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, foreignWorkspace);
        readAllocation(fixture, foreignWarehouseToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_NOT_FOUND"));
    }

    @Test
    void consumedAllocationRemainsReadableAsInactiveWithCurrentConsumedFacts() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult allocationResult = readAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn();
        var fulfillment = json(fixture.fulfillment());
        var allocation = json(allocationResult);
        var line = allocation.get("lines").get(0);

        MvcResult picking = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-starts")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", fixture.fulfillment().getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "physical-read-picking-" + suffix()))
                .andExpect(status().isOk()).andReturn();
        String pickingBody = "{\"allocationVersion\":" + allocation.get("version").asLong()
                + ",\"notes\":\"physical allocation read integration\",\"lines\":[{\"fulfillmentLineId\":\""
                + fulfillment.get("lines").get(0).get("id").asText() + "\",\"skuId\":\""
                + fulfillment.get("lines").get(0).get("skuId").asText() + "\",\"quantity\":1,\"unit\":\"UNIT\",\"physicalAllocationLineId\":\""
                + line.get("physicalAllocationLineId").asText() + "\",\"lotId\":\"" + line.get("lotId").asText()
                + "\",\"warehouseId\":\"" + line.get("warehouseId").asText() + "\"}]}";
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", picking.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "physical-read-confirm-" + suffix())
                        .contentType(MediaType.APPLICATION_JSON).content(pickingBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult packed = fulfillmentStep(fixture, "packing", picked.getResponse().getHeader("ETag"));
        MvcResult staged = fulfillmentStep(fixture, "staging", packed.getResponse().getHeader("ETag"));
        MvcResult ready = fulfillmentStep(fixture, "ready-for-dispatch", staged.getResponse().getHeader("ETag"));
        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", ready.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "physical-read-dispatch-" + suffix()))
                .andExpect(status().isOk());

        MvcResult consumed = readAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn();
        var current = json(consumed);
        var consumedLine = current.get("lines").get(0);
        assertThat(current.get("status").asText()).isEqualTo("CONSUMED");
        assertThat(current.get("version").asLong()).isGreaterThan(allocation.get("version").asLong());
        assertThat(consumed.getResponse().getHeader("ETag")).isEqualTo("\"" + current.get("version").asLong() + "\"");
        assertThat(consumedLine.get("consumedQuantity").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(consumedLine.get("remainingQuantity").decimalValue()).isZero();
    }

    private Fixture createFulfillment() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", "physical-read-order-" + suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Physical allocation read\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(order).get("id").asText();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        List<UUID> backingWarehouses = jdbc.query(
                "select distinct p.warehouse_id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=b.tenant_id and l.workspace_id=b.workspace_id and l.backing_id=b.id "
                        + "join warehouse.inventory_backing_position p on p.tenant_id=l.tenant_id and p.workspace_id=l.workspace_id and p.backing_line_id=l.id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=? and b.status='BACKED'",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(orderId));
        for (UUID backingWarehouse : backingWarehouses) {
            grant(owner, backingWarehouse.toString(), membershipId(WAREHOUSE_EMAIL));
        }
        warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult fulfillment = mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "physical-read-fulfillment-" + suffix())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        var body = json(fulfillment);
        String allocationId = body.get("physicalAllocationId").asText();
        String warehouseId = jdbc.queryForObject(
                "select warehouse_id::text from warehouse.physical_allocation_line where tenant_id=? and workspace_id=? and physical_allocation_id=? limit 1",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(allocationId));
        return new Fixture(body.get("id").asText(), accessToken(WAREHOUSE_EMAIL, "PLATFORM"), warehouseId,
                allocationId, fulfillment);
    }

    private ResultActions readAllocation(Fixture fixture, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/physical-allocation")
                .header("Authorization", "Bearer " + token));
    }

    private MvcResult fulfillmentStep(Fixture fixture, String action, String etag) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/" + action)
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "physical-read-" + action + "-" + suffix()))
                .andExpect(status().isOk()).andReturn();
    }

    private String createWarehouse(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Warehouse " + code + "\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("id").asText();
    }

    private String createWorkspaceForWarehouse(String suffix) {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String slug = "w4-picking-" + suffix.toLowerCase(java.util.Locale.ROOT);
        UUID sourceMembership = UUID.fromString(membershipId(WAREHOUSE_EMAIL));
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenant, "Picking isolation tenant", "w4-pick-tenant-" + suffix.toLowerCase(java.util.Locale.ROOT));
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspace, tenant, "Picking isolation workspace", slug);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "select ?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                        + "from tenant_management.workspace_membership where id=?",
                membership, workspace, sourceMembership);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition "
                        + "where membership_id=?",
                membership, tenant, workspace, sourceMembership);
        return slug;
    }

    private String accessTokenForWorkspace(String email, String workspaceSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
                                + "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("accessToken").asText();
    }

    private MvcResult grant(String owner, String warehouseId, String memberId) throws Exception {
        return mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + memberId + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record Fixture(String fulfillmentId, String warehouseToken, String warehouseId,
                           String allocationId, MvcResult fulfillment) { }
}
