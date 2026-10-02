package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL and HTTP coverage for dispatch instruction revisions and Driver acknowledgements. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "spring.datasource.hikari.minimum-idle=1")
class DeliveryInstructionIT extends NexaWorkflowIntegrationSupport {

    private UUID temperatureFixtureSku;
    private BigDecimal originalTemperatureMinimum;
    private BigDecimal originalTemperatureMaximum;

    @AfterEach
    void restoreChangedSkuTemperaturePolicy() {
        if (temperatureFixtureSku != null) {
            jdbc.update("update catalog_management.sellable_sku set temperature_min=?,temperature_max=? where id=?",
                    originalTemperatureMinimum, originalTemperatureMaximum, temperatureFixtureSku);
        }
    }

    @Test
    void criticalAcknowledgementsGateNewAttemptAndBindToCurrentRevisionAndActor() throws Exception {
        String workdayToken = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        var currentWorkday = mockMvc.perform(get("/api/v1/driver/workdays/current")
                        .header("Authorization", bearer(workdayToken))).andReturn();
        if (currentWorkday.getResponse().getStatus() == 204) {
        mockMvc.perform(post("/api/v1/driver/workdays")
                        .header("Authorization", bearer(workdayToken))
                        .header("Idempotency-Key", "instruction-workday-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                .andExpect(status().isOk());
        }
        AssignedDelivery noInstructions = createAssignedDelivery();
        String noInstructionEtag = deliveryEtag(noInstructions);
        mockMvc.perform(post(driverPath(noInstructions) + "/attempts")
                        .header("Authorization", bearer(noInstructions.token()))
                        .header("If-Match", noInstructionEtag)
                        .header("Idempotency-Key", "instructions-no-critical-" + UUID.randomUUID()))
                .andExpect(status().isCreated());

        AssignedDelivery fixture = createAssignedDelivery();
        MvcResult first = publish(fixture, null, "COLD_CHAIN", "Keep refrigerated", deliveryEtag(fixture));
        UUID instructionId = UUID.fromString(json(first).get("instructionId").asText());
        MvcResult second = publish(fixture, null, "SPECIAL_UNLOADING", "Use the loading dock", deliveryEtag(fixture));
        UUID secondInstructionId = UUID.fromString(json(second).get("instructionId").asText());

        MvcResult read = mockMvc.perform(get(driverPath(fixture) + "/instructions")
                        .header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructionSetVersion").value(2))
                .andExpect(jsonPath("$.instructions[0].critical").value(true))
                .andExpect(jsonPath("$.instructions[0].acknowledged").value(false))
                .andReturn();
        String instructionEtag = read.getResponse().getHeader("ETag");
        String firstAckBody = "{\"instructionIds\":[\"" + instructionId + "\"]}";
        String ackKey = "driver-instruction-ack-" + UUID.randomUUID();

        MvcResult acknowledged = mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", instructionEtag)
                        .header("Idempotency-Key", ackKey).contentType(MediaType.APPLICATION_JSON).content(firstAckBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acknowledgements[0].instructionId").value(instructionId.toString()))
                .andExpect(jsonPath("$.acknowledgements[0].instructionVersion").value(1))
                .andExpect(jsonPath("$.acknowledgements[0].acknowledgedByMembershipId").value(fixture.membershipId().toString()))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        String acknowledgedAt = json(acknowledged).get("acknowledgements").get(0).get("acknowledgedAt").asText();

        mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", instructionEtag)
                        .header("Idempotency-Key", ackKey).contentType(MediaType.APPLICATION_JSON).content(firstAckBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.acknowledgements[0].acknowledgedAt").value(acknowledgedAt));
        mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", instructionEtag)
                        .header("Idempotency-Key", ackKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instructionIds\":[\"" + secondInstructionId + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        MvcResult revised = publish(fixture, instructionId, "COLD_CHAIN", "Keep between 2 and 8 C", deliveryEtag(fixture));
        String staleEtag = instructionEtag;
        String revisedDeliveryEtag = revised.getResponse().getHeader("ETag");
        MvcResult currentRead = mockMvc.perform(get(driverPath(fixture) + "/instructions")
                        .header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructionSetVersion").value(3)).andReturn();
        var revisedInstruction = instruction(currentRead, instructionId);
        assertThat(revisedInstruction.get("instructionVersion").asLong()).isEqualTo(2);
        assertThat(revisedInstruction.get("acknowledged").asBoolean()).isFalse();
        String currentInstructionEtag = currentRead.getResponse().getHeader("ETag");
        mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", staleEtag)
                        .header("Idempotency-Key", "stale-instruction-set-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(firstAckBody))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post(driverPath(fixture) + "/attempts")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", revisedDeliveryEtag)
                        .header("Idempotency-Key", "missing-current-critical-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED"));

        String currentAcks = "{\"instructionIds\":[\"" + instructionId + "\",\"" + secondInstructionId + "\"]}";
        mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", currentInstructionEtag)
                        .header("Idempotency-Key", "driver-current-instructions-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(currentAcks))
                .andExpect(status().isCreated());
        MvcResult started = mockMvc.perform(post(driverPath(fixture) + "/attempts")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", revisedDeliveryEtag)
                        .header("Idempotency-Key", "acknowledged-current-critical-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(started).get("attempt").get("status").asText()).isEqualTo("ACTIVE");

        String outsiderWorkspace = createWorkspaceForLogistics(UUID.randomUUID().toString());
        String outsiderDriverToken = accessTokenForWorkspace(LOGISTICS_EMAIL, outsiderWorkspace);
        mockMvc.perform(get(driverPath(fixture) + "/instructions")
                        .header("Authorization", bearer(outsiderDriverToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(dispatchInstructionsPath(fixture))
                        .header("Authorization", bearer(outsiderDriverToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(driverPath(fixture) + "/instruction-acknowledgements")
                        .header("Authorization", bearer(outsiderDriverToken))
                        .header("If-Match", currentInstructionEtag)
                        .header("Idempotency-Key", ackKey).contentType(MediaType.APPLICATION_JSON).content(firstAckBody))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerInstructionsFreezeAtReadinessAndPreserveBuyerProvenanceInDriverProjection() throws Exception {
        AssignedDelivery fixture = createAssignedDelivery(true);
        var view = mockMvc.perform(get(driverPath(fixture) + "/instructions")
                        .header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instructions[0].sourceKind").value("BUYER"))
                .andExpect(jsonPath("$.instructions[0].recordedByMembershipId").value(membershipId(BUYER_EMAIL)))
                .andExpect(jsonPath("$.instructions[0].critical").value(true)).andReturn();
        assertThat(json(view).toString()).doesNotContain("creditLimit", "pricing", "receivables");
        MvcResult dispatchView = mockMvc.perform(get(dispatchInstructionsPath(fixture))
                        .header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value(fixture.deliveryId().toString()))
                .andExpect(jsonPath("$.instructions[0].sourceKind").value("BUYER"))
                .andExpect(jsonPath("$.instructions[0].recordedByMembershipId").value(membershipId(BUYER_EMAIL)))
                .andReturn();
        String dispatchDeliveryEtag = dispatchView.getResponse().getHeader("ETag");
        assertThat(dispatchDeliveryEtag).isEqualTo("\"" + json(dispatchView).get("deliveryVersion").asLong() + "\"");
        String customerInstructionId = json(dispatchView).get("instructions").get(0).get("id").asText();
        mockMvc.perform(post(dispatchInstructionsPath(fixture))
                        .header("Authorization", bearer(fixture.token()))
                        .header("If-Match", dispatchDeliveryEtag)
                        .header("Idempotency-Key", "dispatch-customer-instruction-edit-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instructionId\":\"" + customerInstructionId
                                + "\",\"kind\":\"NORMAL\",\"content\":\"Dispatch cannot replace Buyer source\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_INSTRUCTION_EDIT_WINDOW_CLOSED"));
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/buyer/sales-orders/" + fixture.salesOrderId() + "/customer-delivery-instructions")
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(false));
        mockMvc.perform(post("/api/v1/buyer/sales-orders/" + fixture.salesOrderId() + "/customer-delivery-instructions")
                        .header("Authorization", bearer(buyer)).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "late-buyer-instruction-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"NORMAL\",\"content\":\"Late edit\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_INSTRUCTION_EDIT_WINDOW_CLOSED"));
        mockMvc.perform(post(driverPath(fixture) + "/attempts").header("Authorization", bearer(fixture.token()))
                        .header("If-Match", deliveryEtag(fixture)).header("Idempotency-Key", "buyer-critical-gate-" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED"));
    }

    @Test
    void transitTemperatureReplaysImmutableReadingAndDoesNotGrantDriverDisposition() throws Exception {
        AssignedDelivery fixture = createAssignedDelivery();
        UUID fulfillment = jdbc.queryForObject("select fulfillment_id from logistics.delivery where id=?", UUID.class, fixture.deliveryId());
        UUID line = jdbc.queryForObject("select id from logistics.fulfillment_line where fulfillment_id=?", UUID.class, fulfillment);
        UUID sku = jdbc.queryForObject("select sku_id from logistics.fulfillment_line where id=?", UUID.class, line);
        originalTemperatureMinimum = jdbc.queryForObject(
                "select temperature_min from catalog_management.sellable_sku where id=?", BigDecimal.class, sku);
        originalTemperatureMaximum = jdbc.queryForObject(
                "select temperature_max from catalog_management.sellable_sku where id=?", BigDecimal.class, sku);
        temperatureFixtureSku = sku;
        jdbc.update("update catalog_management.sellable_sku set temperature_min=0,temperature_max=8 where id=?", sku);
        String path = "/api/v1/driver/deliveries/" + fixture.deliveryId() + "/execution-temperature-readings";
        MvcResult snapshot = mockMvc.perform(get(path).header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].skuId").value(sku.toString())).andReturn();
        String key = "transit-reading-" + UUID.randomUUID();
        String body = "{\"fulfillmentLineId\":\"" + line + "\",\"skuId\":\"" + sku
                + "\",\"affectedQuantity\":1,\"value\":4,\"unit\":\"CELSIUS\",\"occurredAt\":\""
                + java.time.Instant.now().minusSeconds(1) + "\"}";
        MvcResult recorded = mockMvc.perform(post(path).header("Authorization", bearer(fixture.token()))
                        .header("If-Match", snapshot.getResponse().getHeader("ETag")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("WITHIN_RANGE"))
                .andExpect(jsonPath("$.temperatureUnit").value("CELSIUS")).andReturn();
        jdbc.update("update catalog_management.sellable_sku set temperature_min=5,temperature_max=9 where id=?", sku);
        MvcResult replay = mockMvc.perform(post(path).header("Authorization", bearer(fixture.token()))
                        .header("If-Match", snapshot.getResponse().getHeader("ETag")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.minimumCelsius").value(0)).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(json(recorded).get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_execution_temperature_evidence where delivery_id=?", Integer.class, fixture.deliveryId())).isEqualTo(1);
        mockMvc.perform(post(path).header("Authorization", bearer(fixture.token()))
                        .header("If-Match", recorded.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "transit-no-photo-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/execution-holds/" + UUID.randomUUID() + "/dispositions")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", recorded.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "transit-denied-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disposition\":\"RELEASE\",\"reason\":\"Driver report is not disposition authority\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_execution_hold where delivery_id=?", Integer.class, fixture.deliveryId())).isZero();
    }

    private AssignedDelivery createAssignedDelivery() throws Exception { return createAssignedDelivery(false); }

    private AssignedDelivery createAssignedDelivery(boolean customerInstruction) throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", bearer(sales))
                        .header("Idempotency-Key", "delivery-instructions-order-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Delivery instructions integration\","
                                + "\"paymentOption\":\"IMMEDIATE\",\"lines\":[{\"catalogItemId\":\"CAT-0002\","
                                + "\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        UUID orderId = UUID.fromString(json(order).get("id").asText());
        if (customerInstruction) {
            String buyer = accessToken(BUYER_EMAIL, "PORTAL");
            UUID customerInstructionId = UUID.randomUUID();
            String customerInstructionKey = "buyer-instruction-" + UUID.randomUUID();
            String customerInstructionBody = "{\"instructionId\":\"" + customerInstructionId
                    + "\",\"kind\":\"COLD_CHAIN\",\"content\":\"Use refrigerated unloading area\"}";
            mockMvc.perform(post("/api/v1/buyer/sales-orders/" + orderId + "/customer-delivery-instructions")
                            .header("Authorization", bearer(buyer)).header("If-Match", "\"0\"")
                            .header("Idempotency-Key", customerInstructionKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(customerInstructionBody))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.instructions[0].sourceKind").value("BUYER"))
                    .andExpect(jsonPath("$.instructions[0].id").value(customerInstructionId.toString()));
            mockMvc.perform(post("/api/v1/buyer/sales-orders/" + orderId + "/customer-delivery-instructions")
                            .header("Authorization", bearer(buyer)).header("If-Match", "\"0\"")
                            .header("Idempotency-Key", customerInstructionKey)
                            .contentType(MediaType.APPLICATION_JSON).content(customerInstructionBody))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.instructions.length()").value(1));
        }
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        List<UUID> backedWarehouseIds = jdbc.query("select distinct p.warehouse_id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=b.tenant_id and l.workspace_id=b.workspace_id and l.backing_id=b.id "
                        + "join warehouse.inventory_backing_position p on p.tenant_id=l.tenant_id and p.workspace_id=l.workspace_id and p.backing_line_id=l.id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=? and b.status='BACKED'",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), orderId);
        for (UUID backedWarehouseId : backedWarehouseIds) {
            mockMvc.perform(post("/api/v1/warehouses/" + backedWarehouseId + "/access-grants")
                            .header("Authorization", bearer(ownerToken)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                    .andExpect(status().isOk());
        }
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult fulfillmentResult = mockMvc.perform(post("/api/v1/sales-orders/" + json(order).get("id").asText()
                        + "/fulfillments")
                        .header("Authorization", bearer(warehouseToken))
                        .header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-instructions-fulfillment-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        var fulfillment = json(fulfillmentResult);
        UUID fulfillmentId = UUID.fromString(fulfillment.get("id").asText());
        UUID fulfillmentLineId = UUID.fromString(fulfillment.get("lines").get(0).get("id").asText());
        MvcResult allocationResult = mockMvc.perform(get("/api/v1/fulfillments/" + fulfillmentId + "/physical-allocation")
                        .header("Authorization", bearer(warehouseToken)))
                .andExpect(status().isOk()).andReturn();
        var allocation = json(allocationResult);
        var physicalLine = allocation.get("lines").get(0);
        UUID warehouseId = UUID.fromString(physicalLine.get("warehouseId").asText());
        UUID physicalAllocationId = UUID.fromString(fulfillment.get("physicalAllocationId").asText());
        UUID physicalAllocationLineId = UUID.fromString(physicalLine.get("physicalAllocationLineId").asText());
        UUID lotId = UUID.fromString(physicalLine.get("lotId").asText());
        UUID skuId = UUID.fromString(physicalLine.get("skuId").asText());
        long allocationVersion = allocation.get("version").asLong();
        UUID logisticsMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", bearer(ownerToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + logisticsMembership + "\"}"))
                .andExpect(status().isOk());

        String startKey = "delivery-instructions-picking-start-" + UUID.randomUUID();
        MvcResult pickingStarted = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-starts")
                        .header("Authorization", bearer(warehouseToken))
                        .header("If-Match", fulfillmentResult.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", startKey))
                .andExpect(status().isOk()).andReturn();
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-confirmations")
                        .header("Authorization", bearer(warehouseToken))
                        .header("If-Match", pickingStarted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-instructions-pick-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationVersion\":" + allocationVersion + ",\"lines\":[{"
                                + "\"fulfillmentLineId\":\"" + fulfillmentLineId + "\",\"skuId\":\"" + skuId
                                + "\",\"quantity\":1,\"unit\":\"" + physicalLine.get("unit").asText()
                                + "\",\"physicalAllocationLineId\":\"" + physicalAllocationLineId
                                + "\",\"lotId\":\"" + lotId + "\",\"warehouseId\":\"" + warehouseId + "\"}]}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult packed = transition(fulfillmentId, "packing", warehouseToken, picked.getResponse().getHeader("ETag"));
        MvcResult staged = transition(fulfillmentId, "staging", warehouseToken, packed.getResponse().getHeader("ETag"));
        MvcResult ready = transition(fulfillmentId, "ready-for-dispatch", warehouseToken,
                staged.getResponse().getHeader("ETag"));
        String logisticsToken = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        var readiness = json(mockMvc.perform(get("/api/v1/dispatch-readiness/" + fulfillmentId)
                        .header("Authorization", bearer(logisticsToken)))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = "{\"responsibleMembershipId\":\"" + logisticsMembership
                + "\",\"physicalAllocationId\":\"" + readiness.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + readiness.get("physicalAllocationVersion").asLong() + "}";
        MvcResult assignment = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/driver-assignments")
                        .header("Authorization", bearer(logisticsToken))
                        .header("If-Match", ready.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-instructions-assignment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult outgoingCheck = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/outgoing-checks")
                        .header("Authorization", bearer(warehouseToken))
                        .header("If-Match", assignment.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-instructions-outgoing-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalAllocationId\":\"" + physicalAllocationId
                                + "\",\"physicalAllocationVersion\":" + allocationVersion
                                + ",\"observations\":[{\"physicalAllocationLineId\":\"" + physicalAllocationLineId
                                + "\",\"observedLotId\":\"" + lotId
                                + "\",\"observedQuantity\":1}] }"))
                .andExpect(status().isCreated()).andReturn();
        var assignmentView = json(assignment);
        String dispatchBody = "{\"physicalAllocationId\":\"" + physicalAllocationId
                + "\",\"physicalAllocationVersion\":" + allocationVersion
                + ",\"driverAssignmentId\":\"" + assignmentView.get("id").asText()
                + "\",\"driverAssignmentVersion\":" + assignmentView.get("fulfillmentVersion").asLong()
                + ",\"outgoingGoodsCheckId\":\"" + json(outgoingCheck).get("id").asText() + "\"}";
        MvcResult dispatched = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/dispatches")
                        .header("Authorization", bearer(warehouseToken))
                        .header("If-Match", assignment.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "delivery-instructions-handover-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk()).andReturn();
        UUID deliveryId = UUID.fromString(json(dispatched).get("deliveryId").asText());
        return new AssignedDelivery(deliveryId, logisticsMembership, logisticsToken, orderId);
    }

    private MvcResult transition(UUID fulfillmentId, String action, String token, String etag) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/" + action)
                        .header("Authorization", bearer(token)).header("If-Match", etag)
                        .header("Idempotency-Key", "delivery-instructions-" + action + "-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult publish(AssignedDelivery fixture, UUID instructionId, String kind, String content,
                              String deliveryEtag) throws Exception {
        String optionalId = instructionId == null ? "" : "\"instructionId\":\"" + instructionId + "\",";
        return mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/instructions")
                        .header("Authorization", bearer(fixture.token())).header("If-Match", deliveryEtag)
                        .header("Idempotency-Key", "dispatch-instruction-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + optionalId + "\"kind\":\"" + kind + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.critical").value(true)).andReturn();
    }

    private String dispatchInstructionsPath(AssignedDelivery fixture) {
        return "/api/v1/deliveries/" + fixture.deliveryId() + "/instructions";
    }

    private String deliveryEtag(AssignedDelivery fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", bearer(fixture.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private tools.jackson.databind.JsonNode instruction(MvcResult response, UUID instructionId) throws Exception {
        for (var item : json(response).get("instructions")) {
            if (instructionId.toString().equals(item.get("id").asText())) return item;
        }
        throw new AssertionError("Expected current instruction " + instructionId);
    }

    private String createWorkspaceForLogistics(String suffix) {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String normalized = suffix.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String tenantSlug = "instruction-tenant-" + normalized;
        String workspaceSlug = "instruction-workspace-" + normalized;
        UUID sourceMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenant, "Delivery-instruction isolation tenant", tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspace, tenant, "Delivery-instruction isolation workspace", workspaceSlug);
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
        return workspaceSlug;
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

    private static String driverPath(AssignedDelivery fixture) {
        return "/api/v1/driver/deliveries/" + fixture.deliveryId();
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private record AssignedDelivery(UUID deliveryId, UUID membershipId, String token, UUID salesOrderId) { }
}
