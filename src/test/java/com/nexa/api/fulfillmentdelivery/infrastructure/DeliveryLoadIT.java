package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL and HTTP coverage for grouped load planning and transfer. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DeliveryLoadIT extends NexaWorkflowIntegrationSupport {

    @Override
    protected void ensureCommercialInventory() throws Exception {
        super.ensureCommercialInventory(LocalDate.of(2099, 1, 1), "CAT-0004");
    }

    @Test
    void plansMissingReadyWindowAppendOnlyAndFeedsDispatchReadiness() throws Exception {
        ensureCommercialInventory();
        ReadyFulfillment fixture = createReadyFulfillment(Instant.now().plusSeconds(7200),
                Instant.now().plusSeconds(18000), false);

        MvcResult current = mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId())
                        .header("Authorization", bearer(fixture.warehouseToken())))
                .andExpect(status().isOk()).andReturn();
        String path = "/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatch-window-plans";
        String key = "dispatch-window-plan-" + uuid();
        String body = "{\"windowStart\":\"2099-12-31T10:00:00Z\",\"windowEnd\":\"2099-12-31T14:00:00Z\","
                + "\"reason\":\"Customer confirmed receiving hours\"}";
        MvcResult planned = mockMvc.perform(post(path).header("Authorization", bearer(fixture.logisticsToken()))
                        .header("If-Match", current.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.recordedByMembershipId").value(membershipId(LOGISTICS_EMAIL))).andReturn();
        mockMvc.perform(post(path).header("Authorization", bearer(fixture.logisticsToken()))
                        .header("If-Match", current.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(get("/api/v1/dispatch-readiness/" + fixture.fulfillmentId())
                        .header("Authorization", bearer(fixture.logisticsToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.windowSource").value("DISPATCH_PLAN"))
                .andExpect(jsonPath("$.windowStart").value("2099-12-31T10:00:00Z"))
                .andExpect(jsonPath("$.windowEnd").value("2099-12-31T14:00:00Z"));
        MvcResult latest = mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId())
                        .header("Authorization", bearer(fixture.warehouseToken())))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post(path).header("Authorization", bearer(fixture.logisticsToken()))
                        .header("If-Match", latest.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "dispatch-window-second-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_DISPATCH_WINDOW_ALREADY_DEFINED"));
        assertThat(json(planned).get("fulfillmentVersion").asLong()).isGreaterThan(fixture.fulfillmentVersion());
    }

    @Test
    void groupsReadyFulfillmentsAssignsAndAcceptsWholeLoadThenReusesPlannedDeliveries() throws Exception {
        ensureCommercialInventory();
        ReadyFulfillment first = createReadyFulfillment(Instant.now().plusSeconds(7200),
                Instant.now().plusSeconds(18000));
        ReadyFulfillment second = createReadyFulfillment(Instant.now().plusSeconds(10800),
                Instant.now().plusSeconds(21600));
        assertThat(first.warehouseId()).isEqualTo(second.warehouseId());

        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String logisticsMembershipId = membershipId(LOGISTICS_EMAIL);
        long movementsBeforePlan = jdbc.queryForObject("select count(*) from warehouse.stock_movement", Long.class);
        String createKey = "delivery-load-create-" + uuid();
        String createBody = createBody(first, second);
        MvcResult created = mockMvc.perform(post("/api/v1/dispatch/loads")
                        .header("Authorization", bearer(logistics)).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.stops").isArray()).andReturn();
        var load = json(created);
        UUID loadId = UUID.fromString(load.get("id").asText());
        assertThat(load.get("stops")).hasSize(2);
        UUID firstDeliveryId = UUID.fromString(load.get("stops").get(0).get("deliveryId").asText());
        UUID secondDeliveryId = UUID.fromString(load.get("stops").get(1).get("deliveryId").asText());
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery where tenant_id=? and workspace_id=? "
                        + "and fulfillment_id in (?,?) and status='PLANNED'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), first.fulfillmentId(), second.fulfillmentId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement", Long.class))
                .isEqualTo(movementsBeforePlan);

        MvcResult replay = mockMvc.perform(post("/api/v1/dispatch/loads")
                        .header("Authorization", bearer(logistics)).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(loadId.toString());
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_load where id=?", Integer.class, loadId))
                .isEqualTo(1);

        mockMvc.perform(put("/api/v1/dispatch/loads/" + loadId + "/stops")
                        .header("Authorization", bearer(logistics)).header("If-Match", "\"99\"")
                        .header("Idempotency-Key", "delivery-load-stale-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOrder\":[\"" + first.fulfillmentId() + "\",\""
                                + second.fulfillmentId() + "\"],\"reason\":\"stale snapshot\"}"))
                .andExpect(status().isPreconditionFailed());

        String loadPath = "/api/v1/dispatch/loads/" + loadId;
        MvcResult assigned = mockMvc.perform(post(loadPath + "/assignments")
                        .header("Authorization", bearer(logistics)).header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-assign-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverMembershipId\":\"" + logisticsMembershipId
                                + "\",\"vehicleReference\":\"TRUCK-LOAD-1\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(assigned).get("assignedDriverMembershipId").asText()).isEqualTo(logisticsMembershipId);

        var driverLoads = json(mockMvc.perform(get("/api/v1/driver/loads")
                        .header("Authorization", bearer(logistics)))
                .andExpect(status().isOk()).andReturn());
        assertThat(driverLoads.toString()).contains(loadId.toString());
        mockMvc.perform(get("/api/v1/driver/deliveries/" + firstDeliveryId + "/instructions")
                        .header("Authorization", bearer(logistics)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/driver/deliveries/" + secondDeliveryId + "/instructions")
                        .header("Authorization", bearer(logistics)))
                .andExpect(status().isOk());

        MvcResult offered = mockMvc.perform(post(loadPath + "/offers")
                        .header("Authorization", bearer(logistics)).header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-offer-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult accepted = mockMvc.perform(post("/api/v1/driver/loads/" + loadId + "/acceptances")
                        .header("Authorization", bearer(logistics)).header("If-Match", offered.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-accept-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult transferred = mockMvc.perform(post(loadPath + "/handoff-confirmations")
                        .header("Authorization", bearer(logistics)).header("If-Match", accepted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-confirm-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESPONSIBILITY_TRANSFERRED"))
                .andReturn();

        ReadyFulfillment[] fixtures = {first, second};
        UUID[] plannedDeliveryIds = {firstDeliveryId, secondDeliveryId};
        for (int index = 0; index < fixtures.length; index++) {
            UUID handedOver = handOver(fixtures[index]);
            assertThat(handedOver).isEqualTo(plannedDeliveryIds[index]);
            assertThat(jdbc.queryForObject("select count(*) from logistics.delivery where tenant_id=? and workspace_id=? "
                            + "and fulfillment_id=?", Integer.class, UUID.fromString(tenantId()),
                    UUID.fromString(workspaceId()), fixtures[index].fulfillmentId())).isEqualTo(1);
        }
        assertThat(json(transferred).get("status").asText()).isEqualTo("RESPONSIBILITY_TRANSFERRED");

        String foreignWorkspace = createWorkspaceForLogistics(uuid());
        String foreignDriver = accessTokenForWorkspace(LOGISTICS_EMAIL, foreignWorkspace);
        mockMvc.perform(get("/api/v1/driver/loads").header("Authorization", bearer(foreignDriver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(post("/api/v1/driver/loads/" + loadId + "/acceptances")
                        .header("Authorization", bearer(foreignDriver)).header("If-Match", "\"4\"")
                        .header("Idempotency-Key", "foreign-load-accept-" + uuid()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDisjointPlannedFulfillmentWindows() throws Exception {
        ensureCommercialInventory();
        ReadyFulfillment first = createReadyFulfillment(Instant.now().plusSeconds(7200),
                Instant.now().plusSeconds(10800));
        ReadyFulfillment second = createReadyFulfillment(Instant.now().plusSeconds(18000),
                Instant.now().plusSeconds(21600));
        assertThat(first.warehouseId()).isEqualTo(second.warehouseId());
        int loadsBefore = jdbc.queryForObject("select count(*) from logistics.delivery_load where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/dispatch/loads")
                        .header("Authorization", bearer(logistics))
                        .header("Idempotency-Key", "delivery-load-window-conflict-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(first, second)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_load where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(loadsBefore);
    }

    @Test
    void rejectsLoadAcceptanceAndHandoffConfirmationAfterFulfillmentAssignmentRevisionChanges() throws Exception {
        ensureCommercialInventory();
        ReadyFulfillment first = createReadyFulfillment(Instant.now().plusSeconds(7200),
                Instant.now().plusSeconds(18000));
        ReadyFulfillment second = createReadyFulfillment(Instant.now().plusSeconds(10800),
                Instant.now().plusSeconds(21600));
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String driverMembershipId = membershipId(LOGISTICS_EMAIL);
        MvcResult created = mockMvc.perform(post("/api/v1/dispatch/loads")
                        .header("Authorization", bearer(logistics))
                        .header("Idempotency-Key", "delivery-load-stale-driver-create-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(first, second)))
                .andExpect(status().isCreated()).andReturn();
        UUID loadId = UUID.fromString(json(created).get("id").asText());
        String loadPath = "/api/v1/dispatch/loads/" + loadId;
        MvcResult assigned = mockMvc.perform(post(loadPath + "/assignments")
                        .header("Authorization", bearer(logistics))
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-stale-driver-assign-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverMembershipId\":\"" + driverMembershipId
                                + "\",\"vehicleReference\":\"TRUCK-STALE-1\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult offered = mockMvc.perform(post(loadPath + "/offers")
                        .header("Authorization", bearer(logistics))
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-stale-driver-offer-" + uuid()))
                .andExpect(status().isOk()).andReturn();

        String assignmentPath = "/api/v1/fulfillments/" + first.fulfillmentId() + "/driver-assignments";
        MvcResult currentAssignment = mockMvc.perform(get(assignmentPath)
                        .header("Authorization", bearer(logistics)))
                .andExpect(status().isOk()).andReturn();
        var assignment = json(currentAssignment);
        MvcResult changedAssignment = mockMvc.perform(post(assignmentPath + "/plan-changes")
                        .header("Authorization", bearer(logistics))
                        .header("If-Match", currentAssignment.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-stale-driver-plan-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAssignmentId\":\"" + assignment.get("id").asText()
                                + "\",\"expectedAssignmentVersion\":" + assignment.get("fulfillmentVersion").asLong()
                                + ",\"physicalAllocationId\":\"" + first.allocationId()
                                + "\",\"physicalAllocationVersion\":" + first.allocationVersion()
                                + ",\"responsibleMembershipId\":\"" + driverMembershipId
                                + "\",\"plannedDispatchAt\":\"" + Instant.now().plusSeconds(9000) + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(changedAssignment).get("deliveryId").isNull()).isTrue();

        mockMvc.perform(post("/api/v1/driver/loads/" + loadId + "/acceptances")
                        .header("Authorization", bearer(logistics))
                        .header("If-Match", offered.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-stale-driver-accept-" + uuid()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_DRIVER_ASSIGNMENT_STALE"));
        mockMvc.perform(post(loadPath + "/handoff-confirmations")
                        .header("Authorization", bearer(logistics))
                        .header("If-Match", offered.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-load-stale-driver-confirm-" + uuid()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_DRIVER_ASSIGNMENT_STALE"));
        assertThat(jdbc.queryForObject("select status from logistics.delivery_load where id=?", String.class, loadId))
                .isEqualTo("OFFERED");
    }

    private ReadyFulfillment createReadyFulfillment(Instant windowStart, Instant windowEnd) throws Exception {
        return createReadyFulfillment(windowStart, windowEnd, true);
    }

    private ReadyFulfillment createReadyFulfillment(Instant windowStart, Instant windowEnd,
                                                     boolean planWindow) throws Exception {
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String suffix = uuid();
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", bearer(sales)).header("Idempotency-Key", "load-order-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Delivery load integration\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0004\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        UUID salesOrderId = UUID.fromString(json(order).get("id").asText());
        var backingWarehouses = jdbc.query("select distinct p.warehouse_id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=b.tenant_id "
                        + "and l.workspace_id=b.workspace_id and l.backing_id=b.id "
                        + "join warehouse.inventory_backing_position p on p.tenant_id=l.tenant_id "
                        + "and p.workspace_id=l.workspace_id and p.backing_line_id=l.id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=? and b.status='BACKED'",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), salesOrderId);
        assertThat(backingWarehouses).hasSize(1);
        for (UUID backingWarehouse : backingWarehouses) {
            ensureWarehouseGrant(backingWarehouse, membershipId(WAREHOUSE_EMAIL));
        }
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        MvcResult created = mockMvc.perform(post("/api/v1/sales-orders/" + salesOrderId + "/fulfillments")
                        .header("Authorization", bearer(warehouse)).header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "load-fulfillment-" + suffix))
                .andExpect(status().isCreated()).andReturn();
        var fulfillment = json(created);
        UUID fulfillmentId = UUID.fromString(fulfillment.get("id").asText());
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + fulfillmentId + "/physical-allocation")
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andReturn());
        assertThat(allocation.get("lines")).hasSize(1);
        var line = allocation.get("lines").get(0);
        UUID warehouseId = UUID.fromString(line.get("warehouseId").asText());
        ensureWarehouseGrant(warehouseId, membershipId(LOGISTICS_EMAIL));
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");

        MvcResult pickingStarted = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-starts")
                        .header("Authorization", bearer(warehouse)).header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "load-picking-start-" + suffix))
                .andExpect(status().isOk()).andReturn();
        var fulfillmentLine = fulfillment.get("lines").get(0);
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-confirmations")
                        .header("Authorization", bearer(warehouse)).header("If-Match", pickingStarted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "load-picking-confirm-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationVersion\":" + allocation.get("version").asLong() + ",\"lines\":[{"
                                + "\"fulfillmentLineId\":\"" + fulfillmentLine.get("id").asText()
                                + "\",\"skuId\":\"" + line.get("skuId").asText() + "\",\"quantity\":"
                                + line.get("quantity").decimalValue().toPlainString() + ",\"unit\":\""
                                + line.get("unit").asText() + "\",\"physicalAllocationLineId\":\""
                                + line.get("physicalAllocationLineId").asText() + "\",\"lotId\":\""
                                + line.get("lotId").asText() + "\",\"warehouseId\":\"" + warehouseId + "\"}]}"))
                .andExpect(status().isOk()).andReturn();
        String etag = picked.getResponse().getHeader("ETag");
        for (String step : new String[]{"packing", "staging", "ready-for-dispatch"}) {
            MvcResult changed = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/" + step)
                            .header("Authorization", bearer(warehouse)).header("If-Match", etag)
                            .header("Idempotency-Key", "load-" + step + "-" + suffix))
                    .andExpect(status().isOk()).andReturn();
            etag = changed.getResponse().getHeader("ETag");
        }
        if (planWindow) {
            mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/dispatch-window-plans")
                            .header("Authorization", bearer(logistics)).header("If-Match", etag)
                            .header("Idempotency-Key", "load-window-plan-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"windowStart\":\"" + windowStart + "\",\"windowEnd\":\"" + windowEnd
                                    + "\",\"reason\":\"Confirmed customer receiving window\"}"))
                    .andExpect(status().isCreated());
        }
        var readiness = json(mockMvc.perform(get("/api/v1/dispatch-readiness/" + fulfillmentId)
                        .header("Authorization", bearer(logistics)))
                .andExpect(status().isOk()).andReturn());
        assertThat(readiness.get("fulfillmentStatus").asText()).isEqualTo("READY_FOR_DISPATCH");
        assertThat(readiness.get("ready").asBoolean()).isTrue();
        return new ReadyFulfillment(fulfillmentId, salesOrderId, UUID.fromString(fulfillment.get("physicalAllocationId").asText()),
                allocation.get("version").asLong(), UUID.fromString(line.get("physicalAllocationLineId").asText()),
                UUID.fromString(line.get("skuId").asText()), UUID.fromString(line.get("lotId").asText()),
                warehouseId, fulfillmentLine.get("id").asText(), line.get("quantity").decimalValue(),
                line.get("unit").asText(), readiness.get("fulfillmentVersion").asLong(), warehouse, logistics);
    }

    private UUID handOver(ReadyFulfillment fixture) throws Exception {
        String fulfillmentPath = "/api/v1/fulfillments/" + fixture.fulfillmentId();
        MvcResult current = mockMvc.perform(get(fulfillmentPath).header("Authorization", bearer(fixture.warehouseToken())))
                .andExpect(status().isOk()).andReturn();
        MvcResult assignment = mockMvc.perform(get(fulfillmentPath + "/driver-assignments")
                        .header("Authorization", bearer(fixture.logisticsToken())))
                .andExpect(status().isOk()).andReturn();
        MvcResult outgoing = mockMvc.perform(post(fulfillmentPath + "/outgoing-checks")
                        .header("Authorization", bearer(fixture.warehouseToken()))
                        .header("If-Match", current.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "load-outgoing-" + fixture.fulfillmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalAllocationId\":\"" + fixture.allocationId()
                                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                                + ",\"observations\":[{\"physicalAllocationLineId\":\"" + fixture.allocationLineId()
                                + "\",\"observedLotId\":\"" + fixture.lotId()
                                + "\",\"observedQuantity\":" + fixture.quantity().toPlainString() + "}]}"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult latest = mockMvc.perform(get(fulfillmentPath).header("Authorization", bearer(fixture.warehouseToken())))
                .andExpect(status().isOk()).andReturn();
        MvcResult handedOver = mockMvc.perform(post(fulfillmentPath + "/dispatches")
                        .header("Authorization", bearer(fixture.logisticsToken()))
                        .header("If-Match", latest.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "load-handover-" + fixture.fulfillmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalAllocationId\":\"" + fixture.allocationId()
                                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                                + ",\"driverAssignmentId\":\"" + json(assignment).get("id").asText()
                                + "\",\"driverAssignmentVersion\":" + json(assignment).get("fulfillmentVersion").asLong()
                                + ",\"outgoingGoodsCheckId\":\"" + json(outgoing).get("id").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(json(handedOver).get("deliveryId").asText());
    }

    private void ensureWarehouseGrant(UUID warehouseId, String targetMembershipId) throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        var grants = json(mockMvc.perform(get("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        for (var grant : grants) {
            if (!targetMembershipId.equals(grant.path("membershipId").asText())) continue;
            if ("ACTIVE".equals(grant.path("status").asText())) return;
            mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                            .header("Authorization", bearer(owner)).header("If-Match", "\"" + grant.path("version").asLong() + "\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                    .andExpect(status().isOk());
            return;
        }
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                .andExpect(status().isOk());
    }

    private String createWorkspaceForLogistics(String suffix) {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String normalized = suffix.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String tenantSlug = "load-tenant-" + normalized;
        String workspaceSlug = "load-workspace-" + normalized;
        UUID sourceMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)", tenant,
                "Delivery-load isolation tenant", tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)", workspace, tenant,
                "Delivery-load isolation workspace", workspaceSlug);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "select ?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                        + "from tenant_management.workspace_membership where id=?", membership, workspace, sourceMembership);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition where membership_id=?",
                membership, tenant, workspace, sourceMembership);
        return workspaceSlug;
    }

    private String accessTokenForWorkspace(String email, String workspaceSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
                                + "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("accessToken").asText();
    }

    private static String createBody(ReadyFulfillment first, ReadyFulfillment second) {
        return "{\"fulfillmentIds\":[\"" + first.fulfillmentId() + "\",\"" + second.fulfillmentId()
                + "\"],\"stopOrder\":[\"" + first.fulfillmentId() + "\",\"" + second.fulfillmentId()
                + "\"],\"expectedFulfillmentVersions\":{\"" + first.fulfillmentId() + "\":"
                + first.fulfillmentVersion() + ",\"" + second.fulfillmentId() + "\":" + second.fulfillmentVersion()
                + "},\"reason\":\"Same warehouse route\",\"compatibilityAttestation\":{"
                + "\"capacitySufficient\":true,\"handlingCompatible\":true,\"zoneReasonable\":true,"
                + "\"noExclusiveTransportRestriction\":true,\"observation\":\"Dispatch reviewed current order facts\"}}";
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private record ReadyFulfillment(UUID fulfillmentId, UUID salesOrderId, UUID allocationId, long allocationVersion,
                                    UUID allocationLineId, UUID skuId, UUID lotId, UUID warehouseId,
                                    String fulfillmentLineId, java.math.BigDecimal quantity, String unit,
                                    long fulfillmentVersion, String warehouseToken, String logisticsToken) { }
}
