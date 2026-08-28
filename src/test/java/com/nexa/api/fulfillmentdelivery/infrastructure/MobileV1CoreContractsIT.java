package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.notifications.application.model.NotificationModels;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL matrix for the approved Mobile V1 backend contracts. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class MobileV1CoreContractsIT extends NexaWorkflowIntegrationSupport {

    @Autowired
    private PushRoutingService pushRouting;

    @Test
    void resolvesIdentifiersAndRejectsUnsafePhysicalPickingScans() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");

        PhysicalFlow flow = createPickingFlow(warehouse, sales, "identifier-scan-" + uuid(), "2");
        String skuCode = jdbc.queryForObject("select sku_code from catalog_management.sellable_sku where id=?", String.class, flow.skuId());
        String batchNumber = jdbc.queryForObject("select batch_number from warehouse.inventory_lot where id=?", String.class, flow.lotId());
        StockSnapshot beforeResolution = stock(flow.lotId());
        int movementsBeforeResolution = jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?",
                Integer.class, flow.lotId());

        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", "  " + skuCode + " ")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.identifierType").value("SKU_CODE"))
                .andExpect(jsonPath("$.skuId").value(flow.skuId().toString()));
        mockMvc.perform(get("/api/v1/inventory/lots/resolve").param("batchNumber", batchNumber)
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.lotId").value(flow.lotId().toString()));
        assertThat(stock(flow.lotId())).isEqualTo(beforeResolution);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class,
                flow.lotId())).isEqualTo(movementsBeforeResolution);
        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", "UNKNOWN-" + uuid())
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.candidateCount").value(0));

        String originalGtin = jdbc.queryForObject("select gtin from catalog_management.sellable_sku where id=?", String.class, flow.skuId());
        UUID otherSku = jdbc.queryForObject("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id<>? and status='ACTIVE' and visible limit 1",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.skuId());
        String otherGtin = jdbc.queryForObject("select gtin from catalog_management.sellable_sku where id=?", String.class, otherSku);
        String testGtin = "9771234567890";
        try {
            jdbc.update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id in (?,?)",
                    testGtin, flow.skuId(), otherSku);
            mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", testGtin)
                            .header("Authorization", "Bearer " + warehouse))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("AMBIGUOUS"))
                    .andExpect(jsonPath("$.identifierType").value("GTIN"))
                    .andExpect(jsonPath("$.candidateCount").value(2))
                    .andExpect(jsonPath("$.skuId").doesNotExist());
        } finally {
            jdbc.update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id=?",
                    originalGtin, flow.skuId());
            jdbc.update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id=?",
                    otherGtin, otherSku);
        }

        jdbc.update("update catalog_management.sellable_sku set status='INACTIVE',updated_at=current_timestamp,version=version+1 where id=?", flow.skuId());
        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", skuCode)
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_FOUND"));
        jdbc.update("update catalog_management.sellable_sku set status='ACTIVE',updated_at=current_timestamp,version=version+1 where id=?", flow.skuId());

        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion(), "MATCH")
                .andExpect(jsonPath("$.remainingQuantity").value(2));
        scan(warehouse, flow, UUID.randomUUID(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion(), "WRONG_SKU");
        scan(warehouse, flow, flow.skuId(), UUID.randomUUID(), flow.warehouseId(), "2", flow.allocationVersion(), "WRONG_LOT");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), UUID.randomUUID(), "2", flow.allocationVersion(), "WRONG_WAREHOUSE");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "3", flow.allocationVersion(), "INSUFFICIENT_ALLOCATED_QUANTITY");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion() + 1, "STALE_ALLOCATION");

        jdbc.update("update warehouse.inventory_lot set status='EXPIRED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "EXPIRED");
        jdbc.update("update warehouse.inventory_lot set status='QUARANTINED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "QUARANTINED");
        jdbc.update("update warehouse.inventory_lot set status='BLOCKED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "NON_SELLABLE");
        jdbc.update("update warehouse.inventory_lot set status='AVAILABLE',version=version+1 where id=?", flow.lotId());

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "incomplete-physical-ref-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationVersion\":" + flow.allocationVersion() + ",\"lines\":[{\"fulfillmentLineId\":\""
                                + flow.fulfillmentLineId() + "\",\"skuId\":\"" + flow.skuId() + "\",\"quantity\":2,\"unit\":\"UNIT\",\"lotId\":\""
                                + flow.lotId() + "\",\"warehouseId\":\"" + flow.warehouseId() + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHYSICAL_SCAN_REFERENCE_REQUIRED"));

        String pickingBody = pickingBody(flow, "2");
        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", flow.pickingKey())
                        .contentType(MediaType.APPLICATION_JSON).content(pickingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", flow.pickingKey())
                        .contentType(MediaType.APPLICATION_JSON).content(pickingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));
        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "over-pick-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(pickingBody(flow, "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_PICKING_REQUIRED"));

        assertThat(jdbc.queryForObject("select count(*) from logistics.picking_result_line where fulfillment_line_id=? and physical_allocation_line_id=?",
                Integer.class, flow.fulfillmentLineId(), flow.physicalAllocationLineId())).isEqualTo(1);
        assertThat(stock(flow.lotId())).isEqualTo(beforeResolution);
    }

    @Test
    void concurrentPhysicalPickingHasOneWinnerAndOneConflict() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, "picking-race-" + uuid(), "1");
        String body = pickingBody(flow, "1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> concurrentPick(warehouse, flow, body, ready, start, "race-first-" + uuid()));
            Future<MvcResult> second = executor.submit(() -> concurrentPick(warehouse, flow, body, ready, start, "race-second-" + uuid()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult conflict = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
            assertThat(json(conflict).get("code").asText()).isEqualTo("FULFILLMENT_PICKING_REQUIRED");
            assertThat(jdbc.queryForObject("select count(*) from logistics.picking_result where fulfillment_id=?", Integer.class,
                    flow.fulfillmentId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from logistics.picking_result_line where fulfillment_line_id=?", Integer.class,
                    flow.fulfillmentLineId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fefoOverrideRequiresReasonAndRebindsOnlyAValidSameWarehouseLot() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, "override-" + uuid(), "1");
        UUID alternativeLot = insertAlternativeLot(flow, "OVERRIDE-" + uuid(), "5");
        StockSnapshot beforeOverride = stock(flow.lotId());
        String bodyWithoutReason = pickingBody(flow, alternativeLot, true, null, "1");

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "override-unauthorized-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithoutReason))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "override-no-reason-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithoutReason))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERRIDE_NOT_ALLOWED"));
        assertThat(stock(flow.lotId())).isEqualTo(beforeOverride);
        assertThat(jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                alternativeLot)).isEqualByComparingTo("0");

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "override-valid-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pickingBody(flow, alternativeLot, true, "FEFO exception: damaged label", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));

        assertThat(jdbc.queryForObject("select lot_id from warehouse.physical_allocation_line where id=?", UUID.class,
                flow.physicalAllocationLineId())).isEqualTo(alternativeLot);
        assertThat(jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                flow.lotId())).isEqualByComparingTo(beforeOverride.reserved().subtract(BigDecimal.ONE));
        assertThat(jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                alternativeLot)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("select count(*) from warehouse.physical_allocation_event where physical_allocation_id=? and event_type='FEFO_OVERRIDE'",
                Integer.class, jdbc.queryForObject("select physical_allocation_id from warehouse.physical_allocation_line where id=?", UUID.class,
                        flow.physicalAllocationLineId()))).isEqualTo(1);
    }

    @Test
    void deliveryHandoffAndBuyerReceiptRemainSeparateAndRetrySafe() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, "handoff-" + uuid(), "2");
        String pickedEtag = pick(flow, warehouse, "handoff-pick-" + uuid());
        String fulfillmentEtag = pickedEtag;
        MvcResult packed = transition(flow.fulfillmentId(), "/packing", warehouse, fulfillmentEtag, "handoff-pack-" + uuid());
        fulfillmentEtag = packed.getResponse().getHeader("ETag");
        MvcResult staged = transition(flow.fulfillmentId(), "/staging", warehouse, fulfillmentEtag, "handoff-stage-" + uuid());
        fulfillmentEtag = staged.getResponse().getHeader("ETag");
        MvcResult ready = transition(flow.fulfillmentId(), "/ready-for-dispatch", warehouse, fulfillmentEtag, "handoff-ready-" + uuid());
        fulfillmentEtag = ready.getResponse().getHeader("ETag");
        MvcResult dispatched = mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", "handoff-dispatch-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        String deliveryId = json(dispatched).get("deliveryId").asText();
        UUID logisticsMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID logisticsUser = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?", UUID.class, logisticsMembership);
        UUID delivery = UUID.fromString(deliveryId);
        jdbc.update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,responsible_membership_id,operator_id,vehicle_reference,route_name,assigned_at,actor_membership_id) values (?,?,?,?,?,?,?, ?,current_timestamp,?)",
                UUID.randomUUID(), UUID.fromString(tenantId()), UUID.fromString(workspaceId()), delivery, logisticsMembership, logisticsUser,
                "VAN-MOBILE-1", "ICISA-MOBILE", logisticsMembership);

        MvcResult deliveryView = mockMvc.perform(get("/api/v1/deliveries/" + deliveryId)
                        .header("Authorization", "Bearer " + logistics)).andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", deliveryView.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "handoff-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        String attemptBody = "{\"outcome\":\"PARTIAL\",\"lines\":[{\"fulfillmentLineId\":\"" + flow.fulfillmentLineId()
                + "\",\"skuId\":\"" + flow.skuId() + "\",\"attemptedQuantity\":1,\"deliveredQuantity\":1,\"rejectedQuantity\":0,\"cancelledQuantity\":0,\"unit\":\"UNIT\"}]}";
        MvcResult attempt = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "handoff-attempt-" + uuid()).contentType(MediaType.APPLICATION_JSON).content(attemptBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.status").value("PARTIAL")).andReturn();
        String attemptId = json(attempt).get("attemptId").asText();

        String issueKey = "handoff-issue-" + uuid();
        String issueBody = "{\"attemptId\":\"" + attemptId + "\"}";
        MvcResult issued = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics).header("Idempotency-Key", issueKey)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String token = json(issued).get("token").asText();
        assertThat(token).hasSizeGreaterThan(40);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_handoff_token where delivery_id=? and status='ACTIVE'",
                Integer.class, delivery)).isEqualTo(1);
        MvcResult issueReplay = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics).header("Idempotency-Key", issueKey)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist()).andReturn();
        assertThat(json(issueReplay).get("handoffId").asText()).isEqualTo(json(issued).get("handoffId").asText());

        String validateBody = "{\"token\":\"" + token + "\"}";
        mockMvc.perform(post("/api/v1/delivery-handoff/validations").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryId").value(deliveryId))
                .andExpect(jsonPath("$.deliveredQuantity").value(1));
        mockMvc.perform(post("/api/v1/delivery-handoff/validations").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/delivery-handoff/validations").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUYER_ONLY_OPERATION"));

        String receiptKey = "buyer-receipt-" + uuid();
        String receiptBody = "{\"token\":\"" + token + "\",\"decision\":\"ACCEPTED\",\"acceptedQuantity\":1}";
        MvcResult receipt = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON).content(receiptBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.decision").value("ACCEPTED")).andReturn();
        MvcResult receiptReplay = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON).content(receiptBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn();
        assertThat(json(receiptReplay).get("id").asText()).isEqualTo(json(receipt).get("id").asText());
        mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"decision\":\"DISPUTED\",\"acceptedQuantity\":0,\"reason\":\"Different decision\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        mockMvc.perform(post("/api/v1/delivery-handoff/validations").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_HANDOFF_TOKEN_INVALID"));

        assertThat(jdbc.queryForObject("select count(*) from logistics.buyer_receipt_fact where delivery_id=?", Integer.class, delivery)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit.event where event_type='BUYER_RECEIPT_RECORDED' and subject_id=?", Integer.class,
                UUID.fromString(json(receipt).get("id").asText()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select decision from logistics.buyer_receipt_fact where id=?", String.class,
                UUID.fromString(json(receipt).get("id").asText()))).isEqualTo("ACCEPTED");
    }

    @Test
    void nativePushRegistrationRotatesWithoutReturningCredentialsAndRoutesDeferred() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String installation = "mobile-it-" + uuid();
        String firstToken = "provider-token-first-" + uuid();
        String secondToken = "provider-token-second-" + uuid();
        String firstKey = "push-register-" + uuid();
        String firstBody = "{\"installationId\":\"" + installation + "\",\"platform\":\"ios\",\"providerToken\":\"" + firstToken + "\"}";
        MvcResult registered = mockMvc.perform(post("/api/v1/notifications/push-subscriptions")
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", firstKey).contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated()).andReturn();
        String subscriptionId = json(registered).get("id").asText();
        assertThat(registered.getResponse().getContentAsString()).doesNotContain("providerToken", firstToken);
        assertThat(jdbc.queryForObject("select provider_token_hash from notifications.push_subscription where id=?", String.class,
                UUID.fromString(subscriptionId))).hasSize(64).doesNotContain(firstToken);

        String secondBody = "{\"installationId\":\"" + installation + "\",\"platform\":\"IOS\",\"providerToken\":\"" + secondToken + "\"}";
        MvcResult rotated = mockMvc.perform(post("/api/v1/notifications/push-subscriptions")
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-rotate-" + uuid()).contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(rotated).get("id").asText()).isEqualTo(subscriptionId);
        assertThat(json(rotated).get("version").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications.push_subscription where recipient_membership_id=? and installation_id=?",
                Integer.class, UUID.fromString(membershipId(BUYER_EMAIL)), installation)).isEqualTo(1);

        UUID eventId = UUID.randomUUID();
        pushRouting.route(new NotificationModels.NotificationProjection(eventId.toString(), tenantId(), workspaceId(),
                        buyerClientAccountId(), "SalesOrder", UUID.randomUUID().toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED",
                        Instant.now(), Set.of(membershipId(BUYER_EMAIL))), "ORDER_STATUS", "Order confirmed", "Safe notification", "/sales-orders/1");
        assertThat(jdbc.queryForObject("select count(*) from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                Integer.class, UUID.fromString(subscriptionId), eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                String.class, UUID.fromString(subscriptionId), eventId)).isEqualTo("DEFERRED");

        mockMvc.perform(post("/api/v1/notifications/push-subscriptions/" + subscriptionId + "/disable")
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-disable-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(delete("/api/v1/notifications/push-subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-unregister-" + uuid()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from notifications.push_subscription where id=?", String.class,
                UUID.fromString(subscriptionId))).isEqualTo("UNREGISTERED");
    }

    private PhysicalFlow createPickingFlow(String warehouse, String sales, String key, String quantity) throws Exception {
        String orderBody = "{\"clientAccountId\":\"" + buyerClientAccountId()
                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                + "\"deliveryProfileSnapshot\":\"Mobile V1 delivery\",\"paymentOption\":\"IMMEDIATE\","
                + "\"comment\":\"Mobile V1 contract matrix\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":"
                + quantity + ",\"unit\":\"UNIT\"}]}";
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders").header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(order).get("id").asText();
        MvcResult allocated = mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "mobile-start-" + uuid()))
                .andExpect(status().isCreated()).andReturn();
        String fulfillmentId = json(allocated).get("id").asText();
        String lineId = json(allocated).get("lines").get(0).get("id").asText();
        MvcResult pickingStart = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-starts")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", allocated.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "mobile-picking-start-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        PhysicalLine physical = jdbc.queryForObject("select l.id,l.sku_id,l.lot_id,l.warehouse_id,pa.version "
                        + "from warehouse.physical_allocation_line l join warehouse.physical_allocation pa "
                        + "on pa.tenant_id=l.tenant_id and pa.workspace_id=l.workspace_id and pa.id=l.physical_allocation_id "
                        + "where l.physical_allocation_id=(select physical_allocation_id from logistics.fulfillment where id=?)",
                (rs, row) -> new PhysicalLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getLong(5)), UUID.fromString(fulfillmentId));
        return new PhysicalFlow(UUID.fromString(fulfillmentId), UUID.fromString(lineId), physical.skuId(), physical.lotId(),
                physical.warehouseId(), physical.id(), physical.version(), pickingStart.getResponse().getHeader("ETag"),
                "mobile-picking-confirm-" + uuid());
    }

    private String pick(PhysicalFlow flow, String warehouse, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(pickingBody(flow, "2")))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getHeader("ETag");
    }

    private MvcResult concurrentPick(String warehouse, PhysicalFlow flow, String body,
                                     CountDownLatch ready, CountDownLatch start, String key) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Picking race did not start");
        return mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult transition(UUID fulfillmentId, String suffix, String token, String etag, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + suffix)
                        .header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .header("Idempotency-Key", key)).andExpect(status().isOk()).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions scan(String token, PhysicalFlow flow, UUID skuId, UUID lotId,
                                                                      UUID warehouseId, String quantity, long version, String outcome) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/physical-allocation-scan-validations")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillmentId\":\"" + flow.fulfillmentId() + "\",\"physicalAllocationLineId\":\""
                                + flow.physicalAllocationLineId() + "\",\"skuId\":\"" + skuId + "\",\"lotId\":\"" + lotId
                                + "\",\"warehouseId\":\"" + warehouseId + "\",\"quantity\":" + quantity
                                + ",\"unit\":\"UNIT\",\"allocationVersion\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value(outcome));
    }

    private UUID insertAlternativeLot(PhysicalFlow flow, String batchNumber, String quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,version,sku_id) "
                        + "select ?,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,?,current_date+365,current_timestamp,?,0,unit,'AVAILABLE',temperature_range_snapshot,0,sku_id "
                        + "from warehouse.inventory_lot where id=?",
                id, batchNumber, new BigDecimal(quantity), flow.lotId());
        return id;
    }

    private static String pickingBody(PhysicalFlow flow, String quantity) {
        return "{\"allocationVersion\":" + flow.allocationVersion() + ",\"lines\":[{\"fulfillmentLineId\":\""
                + flow.fulfillmentLineId() + "\",\"skuId\":\"" + flow.skuId() + "\",\"quantity\":" + quantity
                + ",\"unit\":\"UNIT\",\"physicalAllocationLineId\":\"" + flow.physicalAllocationLineId()
                + "\",\"lotId\":\"" + flow.lotId() + "\",\"warehouseId\":\"" + flow.warehouseId() + "\"}]}";
    }

    private static String pickingBody(PhysicalFlow flow, UUID lotId, boolean override, String reason, String quantity) {
        return "{\"allocationVersion\":" + flow.allocationVersion() + ",\"lines\":[{\"fulfillmentLineId\":\""
                + flow.fulfillmentLineId() + "\",\"skuId\":\"" + flow.skuId() + "\",\"quantity\":" + quantity
                + ",\"unit\":\"UNIT\",\"physicalAllocationLineId\":\"" + flow.physicalAllocationLineId()
                + "\",\"lotId\":\"" + lotId + "\",\"warehouseId\":\"" + flow.warehouseId()
                + "\",\"fefoOverride\":" + override
                + (reason == null ? "" : ",\"fefoOverrideReason\":\"" + reason + "\"") + "}]}";
    }

    private StockSnapshot stock(UUID lotId) {
        return jdbc.queryForObject("select stock_quantity,reserved_quantity from warehouse.inventory_lot where id=?",
                (rs, row) -> new StockSnapshot(rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity")), lotId);
    }

    private record PhysicalLine(UUID id, UUID skuId, UUID lotId, UUID warehouseId, long version) { }
    private record StockSnapshot(BigDecimal stock, BigDecimal reserved) { }

    private record PhysicalFlow(UUID fulfillmentId, UUID fulfillmentLineId, UUID skuId, UUID lotId, UUID warehouseId,
                                UUID physicalAllocationLineId, long allocationVersion, String pickingEtag, String pickingKey) { }
}
