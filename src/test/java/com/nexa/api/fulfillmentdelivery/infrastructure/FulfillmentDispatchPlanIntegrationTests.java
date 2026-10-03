package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class FulfillmentDispatchPlanIntegrationTests extends NexaWorkflowIntegrationSupport {
    @Test
    void reassignsAndReschedulesWithAppendOnlyHistoryAndExactReplay() throws Exception {
        UUID salesMembership = UUID.fromString(membershipId(SALES_EMAIL));
        UUID logisticsRole = jdbc.queryForObject("select r.id from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.workspace_id is null "
                        + "and r.code='logistics' and r.status='ACTIVE'", UUID.class);
        jdbc.update("insert into tenant_management.membership_role_definition"
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) values (?,?,?,?,current_timestamp)",
                salesMembership, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), logisticsRole);
        try {
            Fixture fixture = readyFulfillment();
            var ready = json(mockMvc.perform(get("/api/v1/dispatch-readiness/" + fixture.fulfillmentId())
                            .header("Authorization", "Bearer " + fixture.coordinatorToken()))
                    .andExpect(status().isOk()).andReturn());
            String initialPath = "/api/v1/fulfillments/" + fixture.fulfillmentId() + "/driver-assignments";
            MvcResult initial = mockMvc.perform(post(initialPath)
                            .header("Authorization", "Bearer " + fixture.coordinatorToken())
                            .header("If-Match", ready.get("fulfillmentVersion").asText().replaceAll("^", "\"").replaceAll("$", "\""))
                            .header("Idempotency-Key", "plan-initial-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"responsibleMembershipId\":\"" + membershipId(LOGISTICS_EMAIL)
                                    + "\",\"physicalAllocationId\":\"" + fixture.allocationId()
                                    + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion() + "}"))
                    .andExpect(status().isOk()).andReturn();
            var initialAssignment = json(initial);
            UUID initialAssignmentId = UUID.fromString(initialAssignment.get("id").asText());
            long initialVersion = initialAssignment.get("fulfillmentVersion").asLong();
            String planPath = initialPath + "/plan-changes";
            Instant firstPlannedAt = Instant.parse("2099-04-01T08:30:00Z");
            String firstBody = planBody(initialAssignmentId, initialVersion, fixture, salesMembership, firstPlannedAt);
            String firstKey = "dispatch-plan-first-" + uuid();
            MvcResult changed = mockMvc.perform(post(planPath)
                            .header("Authorization", "Bearer " + fixture.coordinatorToken())
                            .header("If-Match", initial.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", firstKey)
                            .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                    .andExpect(status().isOk()).andReturn();
            var changedAssignment = json(changed);
            UUID changedAssignmentId = UUID.fromString(changedAssignment.get("id").asText());
            assertThat(changedAssignmentId).isNotEqualTo(initialAssignmentId);
            assertThat(changedAssignment.get("responsibleMembershipId").asText()).isEqualTo(salesMembership.toString());
            assertThat(Instant.parse(changedAssignment.get("plannedDispatchAt").asText())).isEqualTo(firstPlannedAt);
            assertThat(changedAssignment.get("current").asBoolean()).isTrue();

            Instant secondPlannedAt = Instant.parse("2099-04-02T09:45:00Z");
            String scheduleOnlyBody = planBody(changedAssignmentId,
                    changedAssignment.get("fulfillmentVersion").asLong(), fixture, null, secondPlannedAt);
            MvcResult rescheduled = mockMvc.perform(post(planPath)
                            .header("Authorization", "Bearer " + fixture.coordinatorToken())
                            .header("If-Match", changed.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", "dispatch-plan-second-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON).content(scheduleOnlyBody))
                    .andExpect(status().isOk()).andReturn();
            var latest = json(rescheduled);
            assertThat(latest.get("responsibleMembershipId").asText()).isEqualTo(salesMembership.toString());
            assertThat(Instant.parse(latest.get("plannedDispatchAt").asText())).isEqualTo(secondPlannedAt);

            var history = json(mockMvc.perform(get(initialPath + "/history")
                            .header("Authorization", "Bearer " + fixture.coordinatorToken()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(history).hasSize(3);
            assertThat(history.get(0).get("responsibleMembershipId").asText())
                    .isEqualTo(membershipId(LOGISTICS_EMAIL));
            assertThat(history.get(1).get("id").asText()).isEqualTo(changedAssignmentId.toString());
            assertThat(history.get(2).get("id").asText()).isEqualTo(latest.get("id").asText());
            assertThat(history.get(2).get("current").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from logistics.fulfillment_driver_assignment "
                            + "where tenant_id=? and workspace_id=? and fulfillment_id=?",
                    Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.fulfillmentId()))
                    .isEqualTo(3);

            MvcResult replay = mockMvc.perform(post(planPath)
                            .header("Authorization", "Bearer " + fixture.coordinatorToken())
                            .header("If-Match", initial.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", firstKey)
                            .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                    .andExpect(status().isOk()).andReturn();
            assertThat(json(replay).get("id").asText()).isEqualTo(changedAssignmentId.toString());
            assertThat(json(replay).get("current").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from logistics.fulfillment_driver_assignment "
                            + "where tenant_id=? and workspace_id=? and fulfillment_id=?",
                    Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.fulfillmentId()))
                    .isEqualTo(3);

            mockMvc.perform(post(planPath)
                            .header("Authorization", "Bearer " + fixture.coordinatorToken())
                            .header("If-Match", changed.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", "dispatch-plan-stale-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON).content(scheduleOnlyBody))
                    .andExpect(status().isPreconditionFailed());
        } finally {
            jdbc.update("delete from tenant_management.membership_role_definition "
                            + "where membership_id=? and role_id=?", salesMembership, logisticsRole);
        }
    }

    private Fixture readyFulfillment() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", "plan-order-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Dispatch plan integration\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(order).get("id").asText();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        var backingWarehouses = jdbc.query("select distinct p.warehouse_id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=b.tenant_id "
                        + "and l.workspace_id=b.workspace_id and l.backing_id=b.id "
                        + "join warehouse.inventory_backing_position p on p.tenant_id=l.tenant_id "
                        + "and p.workspace_id=l.workspace_id and p.backing_line_id=l.id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=? and b.status='BACKED'",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), UUID.fromString(orderId));
        for (UUID warehouseId : backingWarehouses) ensureWarehouseGrant(owner, warehouseId, membershipId(WAREHOUSE_EMAIL));
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "plan-fulfillment-" + uuid()))
                .andExpect(status().isCreated()).andReturn();
        var fulfillment = json(created);
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + fulfillment.get("id").asText()
                        + "/physical-allocation").header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk()).andReturn());
        var line = allocation.get("lines").get(0);
        ensureWarehouseGrant(owner, UUID.fromString(line.get("warehouseId").asText()), membershipId(LOGISTICS_EMAIL));
        String coordinator = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        Fixture fixture = new Fixture(UUID.fromString(fulfillment.get("id").asText()),
                UUID.fromString(fulfillment.get("physicalAllocationId").asText()),
                line.get("physicalAllocationLineId").asText(), line.get("skuId").asText(), line.get("lotId").asText(),
                line.get("warehouseId").asText(), line.get("quantity").decimalValue(), line.get("unit").asText(),
                allocation.get("version").asLong(), warehouse, coordinator);

        MvcResult started = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-starts")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "plan-pick-start-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "plan-picked-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationVersion\":" + fixture.allocationVersion() + ",\"lines\":[{"
                                + "\"fulfillmentLineId\":\"" + fulfillment.get("lines").get(0).get("id").asText()
                                + "\",\"skuId\":\"" + fixture.skuId() + "\",\"quantity\":"
                                + fixture.quantity().toPlainString() + ",\"unit\":\"" + fixture.unit()
                                + "\",\"physicalAllocationLineId\":\"" + fixture.allocationLineId()
                                + "\",\"lotId\":\"" + fixture.lotId() + "\",\"warehouseId\":\""
                                + fixture.warehouseId() + "\"}]}"))
                .andExpect(status().isOk()).andReturn();
        String etag = picked.getResponse().getHeader("ETag");
        for (String action : new String[]{"packing", "staging", "ready-for-dispatch"}) {
            MvcResult step = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/" + action)
                            .header("Authorization", "Bearer " + warehouse).header("If-Match", etag)
                            .header("Idempotency-Key", "plan-" + action + "-" + uuid()))
                    .andExpect(status().isOk()).andReturn();
            etag = step.getResponse().getHeader("ETag");
        }
        return fixture;
    }

    private void ensureWarehouseGrant(String owner, UUID warehouseId, String targetMembershipId) throws Exception {
        var grants = json(mockMvc.perform(get("/api/v1/warehouses/" + warehouseId + "/access-grants")
                .header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn());
        for (var grant : grants) {
            if (targetMembershipId.equals(grant.path("membershipId").asText())
                    && "ACTIVE".equals(grant.path("status").asText())) return;
        }
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                .andExpect(status().isOk());
    }

    private static String planBody(UUID assignmentId, long version, Fixture fixture,
                                   UUID responsibleMembershipId, Instant plannedAt) {
        return "{\"expectedAssignmentId\":\"" + assignmentId + "\",\"expectedAssignmentVersion\":"
                + version + ",\"physicalAllocationId\":\"" + fixture.allocationId()
                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                + (responsibleMembershipId == null ? "" : ",\"responsibleMembershipId\":\"" + responsibleMembershipId + "\"")
                + ",\"plannedDispatchAt\":\"" + plannedAt + "\"}";
    }

    private record Fixture(UUID fulfillmentId, UUID allocationId, String allocationLineId,
                           String skuId, String lotId, String warehouseId, java.math.BigDecimal quantity,
                           String unit, long allocationVersion, String warehouseToken, String coordinatorToken) { }
}
