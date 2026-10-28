package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.notifications.application.model.NotificationModels;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.notifications.infrastructure.persistence.JdbcPushSubscriptionAdapter;
import com.nexa.api.notifications.infrastructure.persistence.JdbcPushTransactionScopeGuard;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcFulfillmentDocumentSourceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL matrix for the approved Mobile V1 backend contracts. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.buyer-wallet-read.enabled=true",
        "nexa.tenant-business.buyer-wallet-recharge.enabled=true",
        "nexa.tenant-business.purchase-request.enabled=true",
        "nexa.tenant-business.catalog-detail-read.enabled=true",
        "nexa.tenant-business.catalog-management.enabled=true",
        "nexa.tenant-business.payments.enabled=true",
        "nexa.tenant-business.fulfillment-delivery.enabled=true",
        "nexa.tenant-business.warehouse-operations.enabled=true",
        "nexa.tenant-business.local-fixtures.enabled=true",
        "nexa.tenant-business.support-sales-order-read.enabled=true",
        "nexa.tenant-business.business-documents.enabled=true",
        "nexa.tenant-business.business-traceability.enabled=true",
        "nexa.tenant-business.notifications.enabled=true",
        "nexa.business-traceability.worker-delay-ms=3600000",
        "nexa.mobile.delivery-handoff-ttl=PT10S",
        "spring.datasource.hikari.minimum-idle=1"
})
class MobileV1CoreContractsIT extends NexaWorkflowIntegrationSupport {

    @Autowired
    private TenantBusinessTraceabilityWorkerRouter tenantTraceabilityWorkerRouter;

    @Autowired
    private PushProviderPort pushProvider;

    @Test
    void resolvesIdentifiersAndRejectsUnsafePhysicalPickingScans() throws Exception {
        ensureCommercialInventory();
        UUID otherGrantedWarehouse = createGrantedWarehouse();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");

        PhysicalFlow flow = createPickingFlow(warehouse, sales, "identifier-scan-" + uuid(), "2");
        String skuCode = tenantJdbc().queryForObject("select sku_code from catalog_management.sellable_sku where id=?", String.class, flow.skuId());
        String originalBatchNumber = tenantJdbc().queryForObject("select batch_number from warehouse.inventory_lot where id=?", String.class, flow.lotId());
        String batchNumber = "B-RESOLUTION-" + uuid();
        StockSnapshot beforeResolution = stock(flow.lotId());
        int movementsBeforeResolution = tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where lot_id=?",
                Integer.class, flow.lotId());

        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", "  " + skuCode + " ")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.identifierType").value("SKU_CODE"))
                .andExpect(jsonPath("$.skuId").value(flow.skuId().toString()));
        // FEFO can select a transferred lot whose batch also exists in another
        // warehouse. Use a unique fixture batch for resolution, and explicitly
        // verify that duplicate batches remain ambiguous rather than guessed.
        assertThat(tenantJdbc().update("update warehouse.inventory_lot set batch_number=? where tenant_id=? and workspace_id=? and id=?",
                batchNumber, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.lotId())).isEqualTo(1);
        try {
            mockMvc.perform(get("/api/v1/inventory/lots/resolve").param("batchNumber", batchNumber)
                            .header("Authorization", "Bearer " + warehouse))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                    .andExpect(jsonPath("$.candidateCount").value(1))
                    .andExpect(jsonPath("$.lotId").value(flow.lotId().toString()));
            UUID duplicateLot = insertDuplicateBatchInAnotherWarehouse(flow, batchNumber, otherGrantedWarehouse);
            try {
                mockMvc.perform(get("/api/v1/inventory/lots/resolve").param("batchNumber", batchNumber)
                                .header("Authorization", "Bearer " + warehouse))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.outcome").value("AMBIGUOUS"))
                        .andExpect(jsonPath("$.candidateCount").value(2))
                        .andExpect(jsonPath("$.lotId").doesNotExist());
            } finally {
                assertThat(tenantJdbc().update("delete from warehouse.inventory_lot where tenant_id=? and workspace_id=? and id=?",
                        UUID.fromString(tenantId()), UUID.fromString(workspaceId()), duplicateLot)).isEqualTo(1);
            }
        } finally {
            assertThat(tenantJdbc().update("update warehouse.inventory_lot set batch_number=? where tenant_id=? and workspace_id=? and id=?",
                    originalBatchNumber, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.lotId())).isEqualTo(1);
        }
        assertThat(stock(flow.lotId())).isEqualTo(beforeResolution);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class,
                flow.lotId())).isEqualTo(movementsBeforeResolution);
        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", "UNKNOWN-" + uuid())
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.candidateCount").value(0));

        String originalGtin = tenantJdbc().queryForObject("select gtin from catalog_management.sellable_sku where id=?", String.class, flow.skuId());
        UUID otherSku = tenantJdbc().queryForObject("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id<>? and status='ACTIVE' and visible limit 1",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.skuId());
        String otherGtin = tenantJdbc().queryForObject("select gtin from catalog_management.sellable_sku where id=?", String.class, otherSku);
        String testGtin = "9771234567890";
        try {
            tenantJdbc().update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id in (?,?)",
                    testGtin, flow.skuId(), otherSku);
            mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", testGtin)
                            .header("Authorization", "Bearer " + warehouse))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("AMBIGUOUS"))
                    .andExpect(jsonPath("$.identifierType").value("GTIN"))
                    .andExpect(jsonPath("$.candidateCount").value(2))
                    .andExpect(jsonPath("$.skuId").doesNotExist());
        } finally {
            tenantJdbc().update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id=?",
                    originalGtin, flow.skuId());
            tenantJdbc().update("update catalog_management.sellable_sku set gtin=?,updated_at=current_timestamp,version=version+1 where id=?",
                    otherGtin, otherSku);
        }

        tenantJdbc().update("update catalog_management.sellable_sku set status='INACTIVE',updated_at=current_timestamp,version=version+1 where id=?", flow.skuId());
        mockMvc.perform(get("/api/v1/skus/resolve").param("identifier", skuCode)
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_FOUND"));
        tenantJdbc().update("update catalog_management.sellable_sku set status='ACTIVE',updated_at=current_timestamp,version=version+1 where id=?", flow.skuId());

        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion(), "MATCH")
                .andExpect(jsonPath("$.remainingQuantity").value(2));
        scan(warehouse, flow, UUID.randomUUID(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion(), "WRONG_SKU");
        scan(warehouse, flow, flow.skuId(), UUID.randomUUID(), flow.warehouseId(), "2", flow.allocationVersion(), "WRONG_LOT");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), otherGrantedWarehouse, "2", flow.allocationVersion(), "WRONG_WAREHOUSE");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "3", flow.allocationVersion(), "INSUFFICIENT_ALLOCATED_QUANTITY");
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "2", flow.allocationVersion() + 1, "STALE_ALLOCATION");

        tenantJdbc().update("update warehouse.inventory_lot set status='EXPIRED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "EXPIRED");
        tenantJdbc().update("update warehouse.inventory_lot set status='QUARANTINED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "QUARANTINED");
        tenantJdbc().update("update warehouse.inventory_lot set status='BLOCKED',version=version+1 where id=?", flow.lotId());
        scan(warehouse, flow, flow.skuId(), flow.lotId(), flow.warehouseId(), "1", flow.allocationVersion(), "NON_SELLABLE");
        tenantJdbc().update("update warehouse.inventory_lot set status='AVAILABLE',version=version+1 where id=?", flow.lotId());

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

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "allocation-version-without-physical-ref-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationVersion\":" + flow.allocationVersion() + ",\"lines\":[{\"fulfillmentLineId\":\""
                                + flow.fulfillmentLineId() + "\",\"skuId\":\"" + flow.skuId()
                                + "\",\"quantity\":2,\"unit\":\"UNIT\"}]}"))
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

        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.picking_result_line where fulfillment_line_id=? and physical_allocation_line_id=?",
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
            assertThat(tenantJdbc().queryForObject("select count(*) from logistics.picking_result where fulfillment_id=?", Integer.class,
                    flow.fulfillmentId())).isEqualTo(1);
            assertThat(tenantJdbc().queryForObject("select count(*) from logistics.picking_result_line where fulfillment_line_id=?", Integer.class,
                    flow.fulfillmentLineId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void physicalPickingAcceptsAFullSplitAllocationAcrossMultipleLots() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, "picking-split-" + uuid(), "2");
        UUID secondLot = insertAlternativeLot(flow, "SPLIT-" + uuid(), "1");
        PhysicalLine second = splitAllocation(flow, secondLot);

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "picking-split-confirm-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitPickingBody(flow, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));

        assertThat(tenantJdbc().queryForObject("select picked_quantity from logistics.fulfillment_line where id=?", BigDecimal.class,
                flow.fulfillmentLineId())).isEqualByComparingTo("2");
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.picking_result_line where fulfillment_line_id=?",
                Integer.class, flow.fulfillmentLineId())).isEqualTo(2);
        assertThat(tenantJdbc().queryForObject("select coalesce(sum(quantity),0) from logistics.picking_result_line where fulfillment_line_id=?",
                BigDecimal.class, flow.fulfillmentLineId())).isEqualByComparingTo("2");
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

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "override-without-physical-ref-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"fulfillmentLineId\":\"" + flow.fulfillmentLineId()
                                + "\",\"skuId\":\"" + flow.skuId() + "\",\"quantity\":1,\"unit\":\"UNIT\","
                                + "\"fefoOverride\":true,\"fefoOverrideReason\":\"FEFO exception\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERRIDE_NOT_ALLOWED"));
        assertThat(stock(flow.lotId())).isEqualTo(beforeOverride);
        assertThat(tenantJdbc().queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                alternativeLot)).isEqualByComparingTo("0");

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "override-valid-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pickingBody(flow, alternativeLot, true, "FEFO exception: damaged label", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));

        assertThat(tenantJdbc().queryForObject("select lot_id from warehouse.physical_allocation_line where id=?", UUID.class,
                flow.physicalAllocationLineId())).isEqualTo(alternativeLot);
        assertThat(tenantJdbc().queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                flow.lotId())).isEqualByComparingTo(beforeOverride.reserved().subtract(BigDecimal.ONE));
        assertThat(tenantJdbc().queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                alternativeLot)).isEqualByComparingTo("1");
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.physical_allocation_event where physical_allocation_id=? and event_type='FEFO_OVERRIDE'",
                Integer.class, tenantJdbc().queryForObject("select physical_allocation_id from warehouse.physical_allocation_line where id=?", UUID.class,
                        flow.physicalAllocationLineId()))).isEqualTo(1);
    }

    @Test
    void substitutionRequestIsAuthorizedIdempotentAndLeavesAllocationAndStockUnchanged() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, "substitution-request-" + uuid(), "2");
        UUID allocationId = tenantJdbc().queryForObject(
                "select id from warehouse.physical_allocation where tenant_id=? and workspace_id=? and fulfillment_id=?",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.fulfillmentId());
        UUID alternativeLot = insertAlternativeLot(flow, "SUBSTITUTION-" + uuid(), "5");
        String key = "substitution-request-" + uuid();
        String body = "{\"fulfillmentId\":\"" + flow.fulfillmentId() + "\","
                + "\"allocationId\":\"" + allocationId + "\","
                + "\"physicalAllocationLineId\":\"" + flow.physicalAllocationLineId() + "\","
                + "\"expectedLotId\":\"" + flow.lotId() + "\","
                + "\"alternativeLotId\":\"" + alternativeLot + "\","
                + "\"quantity\":2,\"unit\":\"UNIT\",\"reason\":\"Expected lot cannot supply the prepared quantity\"}";
        StockSnapshot originalBefore = stock(flow.lotId());
        StockSnapshot alternativeBefore = stock(alternativeLot);
        int movementCountBefore = tenantJdbc().queryForObject(
                "select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        long allocationVersionBefore = tenantJdbc().queryForObject(
                "select version from warehouse.physical_allocation where tenant_id=? and workspace_id=? and id=?",
                Long.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), allocationId);

        mockMvc.perform(post("/api/v1/inventory/physical-allocation-substitution-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                        .header("Idempotency-Key", "substitution-unauthorized-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/inventory/physical-allocation-substitution-requests")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.expectedLotId").value(flow.lotId().toString()))
                .andExpect(jsonPath("$.alternativeLotId").value(alternativeLot.toString()))
                .andExpect(jsonPath("$.currentAllocationVersion").value(flow.allocationVersion()))
                .andReturn();
        String requestId = json(created).get("id").asText();

        MvcResult replay = mockMvc.perform(post("/api/v1/inventory/physical-allocation-substitution-requests")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn();
        assertThat(json(replay).get("currentAllocationVersion").asLong()).isEqualTo(flow.allocationVersion());

        assertThat(tenantJdbc().queryForObject("select lot_id from warehouse.physical_allocation_line where id=?",
                UUID.class, flow.physicalAllocationLineId())).isEqualTo(flow.lotId());
        assertThat(tenantJdbc().queryForObject("select version from warehouse.physical_allocation where id=?",
                Long.class, allocationId)).isEqualTo(allocationVersionBefore);
        assertThat(stock(flow.lotId())).isEqualTo(originalBefore);
        assertThat(stock(alternativeLot)).isEqualTo(alternativeBefore);
        assertThat(tenantJdbc().queryForObject(
                "select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId())))
                .isEqualTo(movementCountBefore);

        mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", flow.pickingEtag())
                        .header("Idempotency-Key", "substitution-authorized-decision-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pickingBody(flow, alternativeLot, true, "Authorized FEFO exception", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));
        assertThat(tenantJdbc().queryForObject("select version from warehouse.physical_allocation where id=?",
                Long.class, allocationId)).isGreaterThan(allocationVersionBefore);
        StockSnapshot originalAfterDecision = stock(flow.lotId());
        StockSnapshot alternativeAfterDecision = stock(alternativeLot);
        int movementCountAfterDecision = tenantJdbc().queryForObject(
                "select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        MvcResult replayAfterAllocationAdvanced = mockMvc.perform(
                        post("/api/v1/inventory/physical-allocation-substitution-requests")
                                .header("Authorization", "Bearer " + warehouse)
                                .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.currentAllocationVersion").value(flow.allocationVersion()))
                .andReturn();
        assertThat(json(replayAfterAllocationAdvanced).get("id").asText()).isEqualTo(requestId);

        String changedReason = body.replace("cannot supply", "cannot fulfill");
        mockMvc.perform(post("/api/v1/inventory/physical-allocation-substitution-requests")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(changedReason))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        mockMvc.perform(post("/api/v1/inventory/physical-allocation-substitution-requests")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", "\"" + flow.allocationVersion() + "\"")
                        .header("Idempotency-Key", "substitution-stale-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed());

        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.physical_allocation_substitution_request where id=?",
                Integer.class, UUID.fromString(requestId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select lot_id from warehouse.physical_allocation_line where id=?",
                UUID.class, flow.physicalAllocationLineId())).isEqualTo(alternativeLot);
        assertThat(stock(flow.lotId())).isEqualTo(originalAfterDecision);
        assertThat(stock(alternativeLot)).isEqualTo(alternativeAfterDecision);
        assertThat(tenantJdbc().queryForObject(
                "select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId())))
                .isEqualTo(movementCountAfterDecision);
    }

    @Test
    void buyerQrAcceptanceIsIdempotentForHandoffBoundToActiveAttempt() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID driverMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        DriverDeliveryFixture fixture = createDriverDelivery(
                warehouse, sales, driverMembership, "buyer-qr-acceptance-" + uuid());

        MvcResult delivery = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", delivery.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "buyer-qr-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult started = mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "buyer-qr-attempt-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String attemptId = json(started).get("attempt").get("id").asText();

        String issueKey = "buyer-qr-issue-" + uuid();
        MvcResult issued = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics)
                        .header("Idempotency-Key", issueKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String token = json(issued).get("token").asText();
        UUID handoffId = UUID.fromString(json(issued).get("handoffId").asText());
        assertThat(tenantJdbc().queryForObject("select delivery_active_attempt_id from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and id=?", UUID.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), handoffId))
                .isEqualTo(UUID.fromString(attemptId));

        AttemptOutcomeLine line = tenantJdbc().queryForObject(
                "select fl.id,fl.sku_id,fl.dispatched_quantity,fl.unit from logistics.fulfillment_line fl "
                        + "join logistics.fulfillment f on f.tenant_id=fl.tenant_id and f.workspace_id=fl.workspace_id "
                        + "and f.id=fl.fulfillment_id join logistics.delivery d on d.tenant_id=f.tenant_id "
                        + "and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id where d.id=?",
                (rs, row) -> new AttemptOutcomeLine(rs.getObject("id", UUID.class),
                        rs.getObject("sku_id", UUID.class), rs.getBigDecimal("dispatched_quantity"),
                        rs.getString("unit")), fixture.deliveryId());
        String attemptedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String outcome = "{\"outcome\":\"DELIVERED\",\"attemptedAt\":\"" + attemptedAt
                + "\",\"lines\":[{\"fulfillmentLineId\":\"" + line.fulfillmentLineId()
                + "\",\"skuId\":\"" + line.skuId() + "\",\"attemptedQuantity\":"
                + line.quantity().toPlainString() + ",\"deliveredQuantity\":" + line.quantity().toPlainString()
                + ",\"rejectedQuantity\":0,\"cancelledQuantity\":0,\"unit\":\"" + line.unit() + "\"}]}";
        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId()
                        + "/attempts/" + attemptId + "/outcomes")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "buyer-qr-outcome-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(outcome))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.status").value("DELIVERED"));
        assertThat(tenantJdbc().queryForObject("select delivery_attempt_id from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and id=?", UUID.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), handoffId))
                .isEqualTo(UUID.fromString(attemptId));
        assertThat(tenantJdbc().queryForObject("select delivery_active_attempt_id from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and id=?", UUID.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), handoffId)).isNull();

        String acceptanceKey = "buyer-qr-acceptance-" + uuid();
        String acceptanceBody = "{\"token\":\"" + token + "\",\"expectedDeliveryId\":\""
                + fixture.deliveryId() + "\"}";
        MvcResult accepted = mockMvc.perform(post("/api/v1/delivery-handoff/receipts")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", acceptanceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptanceBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryId").value(fixture.deliveryId().toString()))
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.podStatus").value("SEALED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String receiptId = json(accepted).get("receiptId").asText();
        String podId = json(accepted).get("podId").asText();

        mockMvc.perform(post("/api/v1/delivery-handoff/receipts")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", acceptanceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptanceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(receiptId))
                .andExpect(jsonPath("$.podId").value(podId))
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.buyer_receipt_fact "
                        + "where tenant_id=? and workspace_id=? and id=? and delivery_id=? "
                        + "and delivery_attempt_id=? and decision='ACCEPTED'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(receiptId),
                fixture.deliveryId(), UUID.fromString(attemptId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.proof_of_delivery "
                        + "where tenant_id=? and workspace_id=? and id=? and status='SEALED' "
                        + "and buyer_membership_id=? and buyer_receipt_fact_id=?", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(podId),
                UUID.fromString(membershipId(BUYER_EMAIL)),
                UUID.fromString(receiptId))).isEqualTo(1);
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
        MvcResult assignment = assignDriver(flow.fulfillmentId(), warehouse, logistics, fulfillmentEtag,
                UUID.fromString(membershipId(LOGISTICS_EMAIL)), "handoff-assignment-" + uuid());
        fulfillmentEtag = assignment.getResponse().getHeader("ETag");
        MvcResult outgoing = recordMatchingOutgoingCheck(flow.fulfillmentId(), warehouse, fulfillmentEtag,
                "handoff-outgoing-" + uuid());
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + flow.fulfillmentId() + "/physical-allocation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk()).andReturn());
        String dispatchBody = "{\"physicalAllocationId\":\"" + allocation.get("allocationId").asText()
                + "\",\"physicalAllocationVersion\":" + allocation.get("version").asLong()
                + ",\"driverAssignmentId\":\"" + json(assignment).get("id").asText()
                + "\",\"driverAssignmentVersion\":" + json(assignment).get("fulfillmentVersion").asLong()
                + ",\"outgoingGoodsCheckId\":\"" + json(outgoing).get("id").asText() + "\"}";
        MvcResult dispatched = mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", "handoff-dispatch-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk()).andReturn();
        String deliveryId = json(dispatched).get("deliveryId").asText();
        UUID delivery = UUID.fromString(deliveryId);

        MvcResult buyerDeliveryPage = mockMvc.perform(get("/api/v1/buyer/deliveries")
                        .param("page", "0").param("size", "100").param("clientAccountId", uuid())
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        var buyerDeliveryItems = json(buyerDeliveryPage).get("items");
        assertThat(buyerDeliveryItems.toString()).contains("\"id\":\"" + deliveryId + "\"");
        assertThat(json(buyerDeliveryPage).get("total").asLong()).isGreaterThan(0);
        assertThat(buyerDeliveryItems.toString()).doesNotContain(
                "salesOrderId", "clientAccountId", "fulfillmentId", "driverMembershipId", "operatorId");
        mockMvc.perform(get("/api/v1/buyer/deliveries")
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        MvcResult buyerDeliveryDetail = mockMvc.perform(get("/api/v1/buyer/deliveries/" + deliveryId)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deliveryId))
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andReturn();
        assertThat(buyerDeliveryDetail.getResponse().getContentAsString()).doesNotContain(
                "salesOrderId", "clientAccountId", "fulfillmentId", "driverMembershipId", "operatorId");

        MvcResult buyerDeliveryEvents = mockMvc.perform(get("/api/v1/buyer/deliveries/" + deliveryId + "/events")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        assertThat(buyerDeliveryEvents.getResponse().getContentAsString())
                .contains("HANDED_OVER")
                .doesNotContain("actorMembershipId", "reason", "driverMembershipId", "operatorId");

        MvcResult deliveryView = mockMvc.perform(get("/api/v1/deliveries/" + deliveryId)
                        .header("Authorization", "Bearer " + logistics)).andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", deliveryView.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "handoff-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult activeAttempt = mockMvc.perform(post("/api/v1/driver/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "handoff-active-attempt-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery.activeAttempt.id").exists())
                .andReturn();
        String attemptId = json(activeAttempt).get("attempt").get("id").asText();
        assertThat(json(activeAttempt).get("delivery").get("activeAttempt").get("id").asText())
                .isEqualTo(attemptId);
        String attemptBody = "{\"outcome\":\"PARTIAL\",\"lines\":[{\"fulfillmentLineId\":\"" + flow.fulfillmentLineId()
                + "\",\"skuId\":\"" + flow.skuId() + "\",\"attemptedQuantity\":1,\"deliveredQuantity\":1,\"rejectedQuantity\":0,\"cancelledQuantity\":0,\"unit\":\"UNIT\"}]}";

        String issueKey = "handoff-issue-" + uuid();
        String issueBody = "{\"attemptId\":\"" + attemptId + "\"}";
        MvcResult issued = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics).header("Idempotency-Key", issueKey)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String token = json(issued).get("token").asText();
        assertThat(token).hasSizeGreaterThan(40);
        UUID handoffId = UUID.fromString(json(issued).get("handoffId").asText());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_handoff_token where delivery_id=? and status='ACTIVE'",
                Integer.class, delivery)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and id=?", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), handoffId)).isZero();
        MvcResult issueReplay = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics).header("Idempotency-Key", issueKey)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist()).andReturn();
        assertThat(json(issueReplay).get("handoffId").asText()).isEqualTo(json(issued).get("handoffId").asText());

        // The receipt command must not reuse a pre-lock time snapshot. Hold the
        // physical Tenant delivery row, let the handoff expire, then release the lock.
        String issuedToken = token;
        ExecutorService expiryExecutor = Executors.newSingleThreadExecutor();
        try (Connection deliveryLock = tenantJdbc().getDataSource().getConnection()) {
            deliveryLock.setAutoCommit(false);
            try (PreparedStatement statement = deliveryLock.prepareStatement(
                    "select id from logistics.delivery where tenant_id=? and workspace_id=? and id=? for update")) {
                statement.setObject(1, UUID.fromString(tenantId()));
                statement.setObject(2, UUID.fromString(workspaceId()));
                statement.setObject(3, delivery);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("physical Tenant delivery row exists before lock").isTrue();
                }
            }
            Future<MvcResult> expiredReceipt = expiryExecutor.submit(() -> mockMvc.perform(
                    post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                            .header("Authorization", "Bearer " + buyer)
                            .header("Idempotency-Key", "expired-while-locked-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON).content(
                                    "{\"token\":\"" + issuedToken + "\",\"decision\":\"ACCEPTED\",\"acceptedQuantity\":1}"))
                    .andReturn());
            Thread.sleep(500L);
            assertThat(expiredReceipt.isDone()).as("receipt should wait for the delivery row lock").isFalse();
            Thread.sleep(10_500L);
            deliveryLock.commit();

            MvcResult expired = expiredReceipt.get(20, TimeUnit.SECONDS);
            assertThat(expired.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(expired).get("code").asText()).isEqualTo("DELIVERY_HANDOFF_EXPIRED");
        } finally {
            expiryExecutor.shutdownNow();
        }

        // The expired active binding is replaced through the normal command;
        // the rest of this test continues with a fresh token.
        MvcResult renewed = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/handoff-tokens")
                        .header("Authorization", "Bearer " + logistics)
                        .header("Idempotency-Key", "handoff-renewed-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        token = json(renewed).get("token").asText();

        mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", activeAttempt.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "handoff-attempt-outcome-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(attemptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.status").value("PARTIAL"))
                .andExpect(jsonPath("$.attemptId").value(attemptId));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, delivery)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=? and id=?",
                Integer.class, delivery, UUID.fromString(attemptId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_handoff_token "
                        + "where delivery_id=? and delivery_attempt_id=? and delivery_active_attempt_id is null "
                        + "and status='ACTIVE'",
                Integer.class, delivery, UUID.fromString(attemptId))).isEqualTo(1);

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
        String receiptBody = "{\"token\":\"" + token + "\",\"decision\":\"DISPUTED\",\"acceptedQuantity\":0,\"reason\":\"Caja faltante\"}";
        MvcResult receipt = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON).content(receiptBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.decision").value("DISPUTED")).andReturn();
        MvcResult receiptReplay = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON).content(receiptBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn();
        assertThat(json(receiptReplay).get("id").asText()).isEqualTo(json(receipt).get("id").asText());
        String receiptId = json(receipt).get("id").asText();
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(receiptId))
                .andExpect(jsonPath("$[0].deliveryId").value(deliveryId))
                .andExpect(jsonPath("$[0].attemptId").value(attemptId))
                .andExpect(jsonPath("$[0].decision").value("DISPUTED"))
                .andExpect(jsonPath("$[0].reason").value("Caja faltante"))
                .andExpect(jsonPath("$[0].recordedByMembershipId").value(membershipId(BUYER_EMAIL)));

        byte[] evidenceBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};
        String evidenceKey = "buyer-receipt-evidence-" + uuid();
        MvcResult uploadedEvidence = uploadBuyerReceiptEvidence(buyer, evidenceKey, receiptId,
                "receipt-photo.png", "image/png", evidenceBytes)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("BUYER_RECEIPT"))
                .andExpect(jsonPath("$.subjectId").value(receiptId))
                .andExpect(jsonPath("$.requestedByMembershipId").value(membershipId(BUYER_EMAIL)))
                .andReturn();
        String evidenceId = json(uploadedEvidence).get("id").asText();
        assertThat(json(uploadedEvidence).get("lifecycleStatus").asText()).isNotEqualTo("REQUESTED");
        MvcResult evidenceReplay = uploadBuyerReceiptEvidence(buyer, evidenceKey, receiptId,
                "receipt-photo.png", "image/png", evidenceBytes)
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(evidenceReplay).get("id").asText()).isEqualTo(evidenceId);
        byte[] changedEvidenceBytes = evidenceBytes.clone();
        changedEvidenceBytes[changedEvidenceBytes.length - 1]++;
        uploadBuyerReceiptEvidence(buyer, evidenceKey, receiptId, "receipt-photo.png", "image/png", changedEvidenceBytes)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        uploadBuyerReceiptEvidence(sales, "buyer-evidence-forbidden-" + uuid(), receiptId,
                "receipt-photo.png", "image/png", evidenceBytes)
                .andExpect(status().isForbidden());
        uploadBuyerReceiptEvidence(buyer, "buyer-evidence-other-subject-" + uuid(), receiptId,
                "receipt-photo.png", "image/png", evidenceBytes, "PROOF_OF_DELIVERY")
                .andExpect(status().isForbidden());

        UUID buyerMembership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID clientAccount = UUID.fromString(buyerClientAccountId());
        int deactivated = tenantJdbc().update("update sales.client_account_membership set status='INACTIVE' "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=? "
                        + "and status='ACTIVE'",
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), clientAccount, buyerMembership);
        assertThat(deactivated).isEqualTo(1);
        try {
            mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceId)
                            .header("Authorization", "Bearer " + buyer))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/business-document-evidence")
                            .param("subjectType", "BUYER_RECEIPT").param("subjectId", receiptId)
                            .header("Authorization", "Bearer " + buyer))
                    .andExpect(status().isNotFound());
        } finally {
            int restored = tenantJdbc().update("update sales.client_account_membership set status='ACTIVE' "
                            + "where tenant_id=? and workspace_id=? and client_account_id=? "
                            + "and workspace_membership_id=? and status='INACTIVE'",
                    UUID.fromString(tenantId()), UUID.fromString(workspaceId()), clientAccount, buyerMembership);
            assertThat(restored).isEqualTo(1);
        }
        mockMvc.perform(get("/api/v1/business-document-evidence")
                        .param("subjectType", "BUYER_RECEIPT").param("subjectId", receiptId)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(evidenceId));
        MvcResult genericEvidenceList = mockMvc.perform(get("/api/v1/business-document-evidence")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        List<String> genericEvidenceIds = new ArrayList<>();
        json(genericEvidenceList).path("items")
                .forEach(item -> genericEvidenceIds.add(item.path("id").asText()));
        assertThat(genericEvidenceIds).doesNotContain(evidenceId);
        mockMvc.perform(delete("/api/v1/business-document-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/business-document-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/buyer-receipts")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", receiptKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"decision\":\"ACCEPTED\",\"acceptedQuantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        mockMvc.perform(post("/api/v1/delivery-handoff/validations").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_HANDOFF_TOKEN_INVALID"));

        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.buyer_receipt_fact where delivery_id=?", Integer.class, delivery)).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from audit.event where event_type='BUYER_RECEIPT_RECORDED' and subject_id=?", Integer.class,
                UUID.fromString(json(receipt).get("id").asText()))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select decision from logistics.buyer_receipt_fact where id=?", String.class,
                UUID.fromString(receiptId))).isEqualTo("DISPUTED");
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.evidence_object "
                        + "where id=? and requested_by_membership_id=? and subject_type='BUYER_RECEIPT' and subject_id=?",
                Integer.class, UUID.fromString(evidenceId), buyerMembership, UUID.fromString(receiptId))).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions uploadBuyerReceiptEvidence(String token, String key,
            String receiptId, String filename, String contentType, byte[] bytes) throws Exception {
        return uploadBuyerReceiptEvidence(token, key, receiptId, filename, contentType, bytes, "BUYER_RECEIPT");
    }

    private org.springframework.test.web.servlet.ResultActions uploadBuyerReceiptEvidence(String token, String key,
            String subjectId, String filename, String contentType, byte[] bytes, String subjectType) throws Exception {
        return mockMvc.perform(multipart("/api/v1/business-document-evidence")
                .file(new org.springframework.mock.web.MockMultipartFile("file", filename, contentType, bytes))
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key));
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
        assertThat(tenantJdbc().queryForObject("select provider_token_hash from notifications.push_subscription where id=?", String.class,
                UUID.fromString(subscriptionId))).hasSize(64).doesNotContain(firstToken);

        String secondBody = "{\"installationId\":\"" + installation + "\",\"platform\":\"IOS\",\"providerToken\":\"" + secondToken + "\"}";
        MvcResult rotated = mockMvc.perform(post("/api/v1/notifications/push-subscriptions")
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-rotate-" + uuid()).contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(rotated).get("id").asText()).isEqualTo(subscriptionId);
        assertThat(json(rotated).get("version").asLong()).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from notifications.push_subscription where recipient_membership_id=? and installation_id=?",
                Integer.class, UUID.fromString(membershipId(BUYER_EMAIL)), installation)).isEqualTo(1);

        UUID eventId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        var projection = new com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection(
                eventId.toString(), tenant.toString(), workspace.toString(), buyerClientAccountId(), "SalesOrder",
                UUID.randomUUID().toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(),
                Set.of(membershipId(BUYER_EMAIL)));
        tenantTraceabilityWorkerRouter.inTransaction(tenant, workspace, (tenantSession, transactionManager) -> {
            PushRoutingService tenantPushRouting = new PushRoutingService(
                    new JdbcPushSubscriptionAdapter(tenantSession), pushProvider, transactionManager,
                    new JdbcPushTransactionScopeGuard(tenantSession), tenant, workspace);
            tenantPushRouting.route(projection, "ORDER_STATUS", "Order confirmed", "Safe notification", "/sales-orders/1");
            return null;
        });
        assertThat(tenantJdbc().queryForObject("select count(*) from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                Integer.class, UUID.fromString(subscriptionId), eventId)).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select status from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                String.class, UUID.fromString(subscriptionId), eventId)).isEqualTo("DEFERRED");
        assertThat(jdbc.queryForObject("select count(*) from notifications.push_delivery_attempt where event_id=?",
                Integer.class, eventId)).isZero();

        mockMvc.perform(post("/api/v1/notifications/push-subscriptions/" + subscriptionId + "/disable")
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-disable-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(delete("/api/v1/notifications/push-subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + buyer).header("X-Nexa-Client", "NATIVE")
                        .header("Idempotency-Key", "push-unregister-" + uuid()))
                .andExpect(status().isNoContent());
        assertThat(tenantJdbc().queryForObject("select status from notifications.push_subscription where id=?", String.class,
                UUID.fromString(subscriptionId))).isEqualTo("UNREGISTERED");
    }

    @Test
    void driverStartIsSingleActiveConcurrentAndLegacyOutcomeTerminatesSameAttempt() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID driverMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        DriverDeliveryFixture fixture = createDriverDelivery(warehouse, sales, driverMembership, "driver-active-" + uuid());

        MvcResult detail = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeAttempt").doesNotExist()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", detail.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics)).andExpect(status().isOk()).andReturn())
                .get("activeAttempt").isNull()).as("IN_TRANSIT is not an active Delivery Attempt").isTrue();

        String firstKey = "driver-attempt-a-" + uuid();
        String secondKey = "driver-attempt-b-" + uuid();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        MvcResult first;
        MvcResult second;
        try {
            Future<MvcResult> firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                                .header("Authorization", "Bearer " + logistics)
                                .header("If-Match", transit.getResponse().getHeader("ETag"))
                                .header("Idempotency-Key", firstKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn();
            });
            Future<MvcResult> secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                                .header("Authorization", "Bearer " + logistics)
                                .header("If-Match", transit.getResponse().getHeader("ETag"))
                                .header("Idempotency-Key", secondKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first = firstFuture.get(30, TimeUnit.SECONDS);
            second = secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(first.getResponse().getStatus()).isIn(200, 201);
        assertThat(second.getResponse().getStatus()).isIn(200, 201);
        String attemptId = json(first).get("attempt").get("id").asText();
        assertThat(json(second).get("attempt").get("id").asText()).isEqualTo(attemptId);
        assertThat(json(first).get("delivery").get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(json(first).get("delivery").get("activeAttempt").get("id").asText()).isEqualTo(attemptId);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isZero();

        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", firstKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempt.id").value(attemptId));

        mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", first.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "legacy-terminal-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"FAILED\",\"failureReason\":\"Recipient unavailable\",\"lines\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(attemptId));
        assertThat(tenantJdbc().queryForObject("select attempt_number from logistics.delivery_attempt where id=?",
                Integer.class, UUID.fromString(attemptId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);
    }

    @Test
    void driverCurrentAndLegacyMyDeliveryReadsHideAnotherAssignment() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String otherDriverEmail = createOtherLogisticsDriver();
        String otherDriver = accessToken(otherDriverEmail, "PLATFORM");
        UUID otherDriverMembership = UUID.fromString(membershipId(otherDriverEmail));
        DriverDeliveryFixture fixture = createDriverDelivery(warehouse, sales, logistics, otherDriverMembership,
                "driver-other-" + uuid());

        MvcResult currentList = mockMvc.perform(get("/api/v1/driver/deliveries")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult legacyList = mockMvc.perform(get("/api/v1/my-deliveries")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        assertThat(currentList.getResponse().getContentAsString()).doesNotContain(fixture.deliveryId().toString());
        assertThat(json(legacyList).get("items").toString()).doesNotContain(fixture.deliveryId().toString());
        mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/my-deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + otherDriver))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", fixture.etag())
                        .header("Idempotency-Key", "driver-wrong-assignment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isZero();
    }

    @Test
    void driverOutcomeRequiresCurrentAssignmentAndAttemptAndReplaysOnlySameCommand() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID driverMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        DriverDeliveryFixture fixture = createDriverDelivery(
                warehouse, sales, driverMembership, "driver-outcome-" + uuid());

        MvcResult detail = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", detail.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-outcome-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult started = mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-outcome-start-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String attemptId = json(started).get("attempt").get("id").asText();
        String startEtag = started.getResponse().getHeader("ETag");
        assertThat(json(mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn()).get("outcomeLines").size()).isEqualTo(1);
        AttemptOutcomeLine line = tenantJdbc().queryForObject(
                "select fl.id,fl.sku_id,fl.dispatched_quantity,fl.unit from logistics.fulfillment_line fl "
                        + "join logistics.fulfillment f on f.tenant_id=fl.tenant_id and f.workspace_id=fl.workspace_id "
                        + "and f.id=fl.fulfillment_id join logistics.delivery d on d.tenant_id=f.tenant_id "
                        + "and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id where d.id=?",
                (rs, row) -> new AttemptOutcomeLine(rs.getObject("id", UUID.class),
                        rs.getObject("sku_id", UUID.class), rs.getBigDecimal("dispatched_quantity"),
                        rs.getString("unit")), fixture.deliveryId());
        String body = "{\"outcome\":\"DELIVERED\",\"failureReason\":\"a|b\",\"attemptedAt\":\"2026-09-30T12:30:00Z\",\"notes\":\"c\",\"lines\":[{\"fulfillmentLineId\":\""
                + line.fulfillmentLineId() + "\",\"skuId\":\"" + line.skuId()
                + "\",\"attemptedQuantity\":" + line.quantity().toPlainString()
                + ",\"deliveredQuantity\":" + line.quantity().toPlainString()
                + ",\"rejectedQuantity\":0,\"cancelledQuantity\":0,\"unit\":\""
                + line.unit() + "\"}]}";
        String outcomeKey = "driver-outcome-" + uuid();
        String path = "/api/v1/driver/deliveries/" + fixture.deliveryId()
                + "/attempts/" + attemptId + "/outcomes";

        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId()
                        + "/attempts/" + UUID.randomUUID() + "/outcomes")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", startEtag)
                        .header("Idempotency-Key", "wrong-attempt-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);

        MvcResult recorded = mockMvc.perform(post(path).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", startEtag).header("Idempotency-Key", outcomeKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(attemptId)).andReturn();
        long completedVersion = json(recorded).get("delivery").get("version").asLong();
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_quantity_outcome where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);

        mockMvc.perform(post(path).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", startEtag).header("Idempotency-Key", outcomeKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.delivery.version").value(completedVersion));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);

        mockMvc.perform(post(path).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", startEtag).header("Idempotency-Key", outcomeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"notes\":\"c\"", "\"notes\":\"changed\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        mockMvc.perform(post(path).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", startEtag).header("Idempotency-Key", outcomeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"failureReason\":\"a|b\"", "\"failureReason\":\"a\"")
                                .replace("\"notes\":\"c\"", "\"notes\":\"b|c\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        mockMvc.perform(post(path).header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", startEtag).header("Idempotency-Key", outcomeKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);
    }

    @Test
    void driverArrivalIsScopedIdempotentAndLeavesDeliveryOpen() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID driverMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        DriverDeliveryFixture fixture = createDriverDelivery(
                warehouse, sales, driverMembership, "driver-arrival-" + uuid());

        MvcResult detail = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", detail.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-arrival-transit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        MvcResult started = mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-arrival-start-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String attemptId = json(started).get("attempt").get("id").asText();
        String path = "/api/v1/driver/deliveries/" + fixture.deliveryId()
                + "/attempts/" + attemptId + "/arrivals";
        String key = "driver-arrival-signal-" + uuid();

        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId()
                        + "/attempts/" + UUID.randomUUID() + "/arrivals")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-arrival-wrong-attempt-" + uuid()))
                .andExpect(status().isNotFound());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_event "
                        + "where delivery_id=? and event_type='DRIVER_ARRIVED'", Integer.class, fixture.deliveryId()))
                .isZero();

        MvcResult first = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryId").value(fixture.deliveryId().toString()))
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        String eventId = json(first).get("id").asText();
        String arrivedAt = json(first).get("arrivedAt").asText();
        assertThat(Instant.parse(arrivedAt).truncatedTo(ChronoUnit.MICROS)).isEqualTo(Instant.parse(arrivedAt));
        long arrivalVersion = json(first).get("deliveryVersion").asLong();
        assertThat(arrivalVersion).isEqualTo(Long.parseLong(started.getResponse().getHeader("ETag").replace("\"", "")) + 1);

        mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.arrivedAt").value(arrivedAt))
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", "\"" + arrivalVersion + "\"")
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        MvcResult current = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.activeAttempt.id").value(attemptId))
                .andExpect(jsonPath("$.arrival.id").value(eventId))
                .andExpect(jsonPath("$.arrival.attemptId").value(attemptId))
                .andReturn();
        assertThat(json(current).get("version").asLong()).isEqualTo(arrivalVersion);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_event "
                        + "where delivery_id=? and event_type='DRIVER_ARRIVED'", Integer.class, fixture.deliveryId()))
                .isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? "
                        + "and operation='DRIVER_ARRIVAL' and idempotency_key=? and resource_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                driverMembership, key, UUID.fromString(eventId))).isEqualTo(1);
    }

    @Test
    void driverProofIsBoundToOwnedFinalAttemptAndSubjectScopedEvidence() throws Exception {
        ensureCommercialInventory();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID driverMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        DriverDeliveryFixture fixture = createDriverDelivery(
                warehouse, sales, driverMembership, "driver-proof-" + uuid());
        TerminalAttemptFixture terminal = completeDriverDelivery(fixture, logistics, "driver-proof-complete-" + uuid());
        var completedDispatchWork = json(mockMvc.perform(get("/api/v1/dispatch-readiness")
                        .param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn());
        List<tools.jackson.databind.JsonNode> completedWorkItems = new ArrayList<>();
        completedDispatchWork.get("items").forEach(completedWorkItems::add);
        var completedDeliveryWork = completedWorkItems.stream()
                .filter(item -> fixture.deliveryId().toString().equals(item.path("deliveryId").asText()))
                .findFirst().orElseThrow();
        assertThat(completedDeliveryWork.path("deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(completedDeliveryWork.path("fulfillmentStatus").asText()).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/v1/deliveries/" + fixture.deliveryId() + "/pod")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isNoContent());
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and aggregate_type='Delivery' and aggregate_id=? "
                        + "and event_type='DeliveryCompleted.v1'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.deliveryId())).isZero();
        String createPath = "/api/v1/driver/deliveries/" + fixture.deliveryId()
                + "/attempts/" + terminal.attemptId() + "/proof-of-delivery";
        String capturedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String createBody = "{\"receiverName\":\"Verified receiver\",\"capturedAt\":\"" + capturedAt
                + "\",\"notes\":\"Package handed over\"}";

        mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId()
                        + "/attempts/" + UUID.randomUUID() + "/proof-of-delivery")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", terminal.deliveryEtag())
                        .header("Idempotency-Key", "driver-proof-wrong-attempt-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isNotFound());

        String createKey = "driver-proof-create-" + uuid();
        MvcResult pending = mockMvc.perform(post(createPath)
                        .header("Authorization", "Bearer " + logistics).header("If-Match", terminal.deliveryEtag())
                        .header("Idempotency-Key", createKey).contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptId").value(terminal.attemptId()))
                .andExpect(jsonPath("$.actorMembershipId").value(driverMembership.toString()))
                .andExpect(jsonPath("$.photoEvidenceObjectId").doesNotExist()).andReturn();
        UUID podId = UUID.fromString(json(pending).get("id").asText());
        String pendingEtag = pending.getResponse().getHeader("ETag");
        mockMvc.perform(post(createPath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", terminal.deliveryEtag()).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(podId.toString()))
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post(createPath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", terminal.deliveryEtag()).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody.replace("Package handed over", "Changed notes")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        UUID clientAccountId = tenantJdbc().queryForObject("select sales_order.client_account_id from logistics.delivery delivery "
                        + "join logistics.fulfillment fulfillment on fulfillment.id=delivery.fulfillment_id "
                        + "join sales.sales_order sales_order on sales_order.id=fulfillment.sales_order_id where delivery.id=?",
                UUID.class, fixture.deliveryId());
        UUID wrongSubjectEvidence = seedAvailablePodEvidence(clientAccountId, UUID.randomUUID(), driverMembership);
        UUID driverUser = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, driverMembership);
        JdbcFulfillmentDocumentSourceQuery documentSource = new JdbcFulfillmentDocumentSourceQuery(tenantJdbc());
        assertThat(documentSource.findPod(UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId)).isEmpty();
        assertThat(documentSource.findPodEvidenceSubject(UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                podId, driverMembership, driverUser).map(
                com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery.ProofOfDelivery::status))
                .contains("PENDING");

        int generationRequestsBefore = tenantJdbc().queryForObject("select count(*) from business_documents.document_generation_request "
                + "where tenant_id=? and workspace_id=? and subject_type='PROOF_OF_DELIVERY' and subject_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId);
        int documentsBefore = tenantJdbc().queryForObject("select count(*) from business_documents.business_document "
                + "where tenant_id=? and workspace_id=? and subject_type='PROOF_OF_DELIVERY' and subject_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId);
        mockMvc.perform(post("/api/v1/business-document-generation-requests")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "driver-proof-draft-report-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"PROOF_OF_DELIVERY\",\"subjectId\":\"" + podId
                                + "\",\"documentType\":\"POD_REPORT\",\"format\":\"PDF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.document_generation_request "
                + "where tenant_id=? and workspace_id=? and subject_type='PROOF_OF_DELIVERY' and subject_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId))
                .isEqualTo(generationRequestsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.business_document "
                + "where tenant_id=? and workspace_id=? and subject_type='PROOF_OF_DELIVERY' and subject_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId))
                .isEqualTo(documentsBefore);

        byte[] podPhoto = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};
        String otherDriverEmail = createOtherLogisticsDriver();
        String otherDriver = accessToken(otherDriverEmail, "PLATFORM");
        mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "other-driver-proof.png", "image/png", podPhoto))
                        .param("subjectType", "PROOF_OF_DELIVERY")
                        .param("subjectId", podId.toString())
                        .header("Authorization", "Bearer " + otherDriver)
                        .header("Idempotency-Key", "other-driver-proof-upload-" + uuid()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(tenantJdbc().queryForObject("select count(*) from business_documents.evidence_object "
                + "where tenant_id=? and workspace_id=? and subject_type='PROOF_OF_DELIVERY' and subject_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId)).isZero();

        MvcResult uploadedPodEvidence = mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "driver-proof.png", "image/png", podPhoto))
                        .param("subjectType", "PROOF_OF_DELIVERY")
                        .param("subjectId", podId.toString())
                        .header("Authorization", "Bearer " + logistics)
                        .header("Idempotency-Key", "driver-proof-upload-" + uuid()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("PROOF_OF_DELIVERY"))
                .andExpect(jsonPath("$.subjectId").value(podId.toString()))
                .andExpect(jsonPath("$.requestedByMembershipId").value(driverMembership.toString()))
                .andExpect(jsonPath("$.lifecycleStatus").value("AVAILABLE")).andReturn();
        UUID matchingEvidence = UUID.fromString(json(uploadedPodEvidence).get("id").asText());
        String evidencePath = createPath + "/" + podId + "/evidence";
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", pendingEtag).header("Idempotency-Key", "driver-proof-wrong-subject-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PHOTO\",\"evidenceObjectId\":\"" + wrongSubjectEvidence + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.proof_of_delivery where id=? and status='PENDING' "
                        + "and photo_evidence_object_id is null", Integer.class, podId)).isEqualTo(1);

        String evidenceKey = "driver-proof-attach-" + uuid();
        String evidenceBody = "{\"kind\":\"PHOTO\",\"evidenceObjectId\":\"" + matchingEvidence + "\"}";
        MvcResult captured = mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", pendingEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(evidenceBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.photoEvidenceObjectId").value(matchingEvidence.toString()))
                .andExpect(jsonPath("$.attemptId").value(terminal.attemptId())).andReturn();
        String capturedAtServer = json(captured).get("capturedAt").asText();
        String capturedEtag = captured.getResponse().getHeader("ETag");
        mockMvc.perform(get("/api/v1/deliveries/" + fixture.deliveryId() + "/pod")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(podId.toString()))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(header().string("ETag", capturedEtag));
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", pendingEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(evidenceBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(podId.toString()))
                .andExpect(jsonPath("$.capturedAt").value(capturedAtServer))
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", capturedEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PHOTO\",\"evidenceObjectId\":\"" + wrongSubjectEvidence + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.proof_of_delivery where delivery_id=?",
                Integer.class, fixture.deliveryId())).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_event where delivery_id=? "
                        + "and attempt_id=? and event_type='POD_CAPTURED' and actor_membership_id=?",
                Integer.class, fixture.deliveryId(), UUID.fromString(terminal.attemptId()), driverMembership)).isEqualTo(1);
        assertThat(documentSource.findSealedPodForDelivery(UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), fixture.deliveryId())).isEmpty();
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and aggregate_type='Delivery' and aggregate_id=? "
                        + "and event_type='DeliveryCompleted.v1'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.deliveryId())).isZero();

        String sealPath = "/api/v1/deliveries/" + fixture.deliveryId() + "/pod/seals";
        String sealKey = "driver-proof-seal-" + uuid();
        MvcResult sealed = mockMvc.perform(post(sealPath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", capturedEtag).header("Idempotency-Key", sealKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(podId.toString()))
                .andExpect(jsonPath("$.status").value("SEALED")).andReturn();
        mockMvc.perform(get("/api/v1/deliveries/" + fixture.deliveryId() + "/pod")
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(header().string("ETag", sealed.getResponse().getHeader("ETag")));
        String sealedAt = json(sealed).get("sealedAt").asText();
        assertThat(sealedAt).isNotBlank();
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and aggregate_type='Delivery' and aggregate_id=? "
                        + "and event_type='DeliveryCompleted.v1'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.deliveryId()))
                .as("driver POD sealing alone does not publish completion without a Buyer receipt")
                .isZero();
        var documentPod = documentSource.findPod(UUID.fromString(tenantId()), UUID.fromString(workspaceId()), podId)
                .orElseThrow();
        assertThat(documentPod.status()).isEqualTo("SEALED");
        assertThat(documentPod.deliveryId()).isEqualTo(fixture.deliveryId());
        assertThat(documentPod.dispatchId()).isNull();
        assertThat(documentPod.salesOrderId()).isNotNull();
        assertThat(documentPod.completedAt()).isEqualTo(Instant.parse(sealedAt));
        assertThat(documentSource.findSealedPodForDelivery(UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), fixture.deliveryId())).contains(documentPod);

        mockMvc.perform(post(sealPath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", capturedEtag).header("Idempotency-Key", sealKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(podId.toString()))
                .andExpect(jsonPath("$.status").value("SEALED"));
        mockMvc.perform(post(sealPath).header("Authorization", "Bearer " + logistics)
                        .header("If-Match", sealed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-proof-seal-other-" + uuid()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POD_NOT_CAPTURED"));
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and aggregate_type='Delivery' and aggregate_id=? "
                        + "and event_type='DeliveryCompleted.v1'", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.deliveryId())).isZero();
    }

    private TerminalAttemptFixture completeDriverDelivery(DriverDeliveryFixture fixture, String logistics,
                                                           String key) throws Exception {
        MvcResult current = mockMvc.perform(get("/api/v1/driver/deliveries/" + fixture.deliveryId())
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + fixture.deliveryId() + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", current.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key + "-transit"))
                .andExpect(status().isOk()).andReturn();
        MvcResult started = mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", transit.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key + "-start")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String attemptId = json(started).get("attempt").get("id").asText();
        AttemptOutcomeLine line = tenantJdbc().queryForObject(
                "select fl.id,fl.sku_id,fl.dispatched_quantity,fl.unit from logistics.fulfillment_line fl "
                        + "join logistics.fulfillment f on f.tenant_id=fl.tenant_id and f.workspace_id=fl.workspace_id "
                        + "and f.id=fl.fulfillment_id join logistics.delivery d on d.tenant_id=f.tenant_id "
                        + "and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id where d.id=?",
                (rs, row) -> new AttemptOutcomeLine(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("dispatched_quantity"), rs.getString("unit")), fixture.deliveryId());
        String timestamp = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String body = "{\"outcome\":\"DELIVERED\",\"attemptedAt\":\"" + timestamp
                + "\",\"lines\":[{\"fulfillmentLineId\":\"" + line.fulfillmentLineId()
                + "\",\"skuId\":\"" + line.skuId() + "\",\"attemptedQuantity\":" + line.quantity().toPlainString()
                + ",\"deliveredQuantity\":" + line.quantity().toPlainString()
                + ",\"rejectedQuantity\":0,\"cancelledQuantity\":0,\"unit\":\"" + line.unit() + "\"}]}";
        MvcResult completed = mockMvc.perform(post("/api/v1/driver/deliveries/" + fixture.deliveryId()
                        + "/attempts/" + attemptId + "/outcomes")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key + "-outcome")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return new TerminalAttemptFixture(attemptId, completed.getResponse().getHeader("ETag"));
    }

    private UUID seedAvailablePodEvidence(UUID clientAccountId, UUID subjectId, UUID actorMembershipId) {
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "evidence/test/driver-pod-" + uuid() + ".jpg";
        String checksum = "a".repeat(64);
        Instant now = Instant.now();
        tenantJdbc().update("insert into business_documents.object_storage_object "
                        + "(object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?)",
                objectKey, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), "nexa-private", checksum,
                "image/jpeg", 8L, true, java.sql.Timestamp.from(now));
        tenantJdbc().update("insert into business_documents.evidence_object "
                        + "(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,"
                        + "declared_content_type,detected_content_type,original_filename,checksum_sha256,byte_size,created_at,scanned_at,"
                        + "requested_by_membership_id,idempotency_key,scan_attempt_count,next_scan_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,'AVAILABLE','image/jpeg','image/jpeg','driver-proof.jpg',?,?,?, ?,?,?,0,?,?)",
                evidenceId, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), clientAccountId,
                "PROOF_OF_DELIVERY", subjectId, objectKey, checksum, 8L, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), actorMembershipId, "seed-driver-proof-" + uuid(),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return evidenceId;
    }

    private record TerminalAttemptFixture(String attemptId, String deliveryEtag) { }

    private DriverDeliveryFixture createDriverDelivery(String warehouse, String sales, UUID assignedMembership,
                                                       String key) throws Exception {
        return createDriverDelivery(warehouse, sales, accessToken(LOGISTICS_EMAIL, "PLATFORM"), assignedMembership, key);
    }

    private DriverDeliveryFixture createDriverDelivery(String warehouse, String sales, String logistics,
                                                       UUID assignedMembership, String key) throws Exception {
        ensureActiveDriverWorkday(logistics, key + "-workday");
        PhysicalFlow flow = createPickingFlow(warehouse, sales, key, "2");
        String picked = pick(flow, warehouse, key + "-pick");
        MvcResult packed = transition(flow.fulfillmentId(), "/packing", warehouse, picked, key + "-pack");
        MvcResult staged = transition(flow.fulfillmentId(), "/staging", warehouse,
                packed.getResponse().getHeader("ETag"), key + "-stage");
        MvcResult ready = transition(flow.fulfillmentId(), "/ready-for-dispatch", warehouse,
                staged.getResponse().getHeader("ETag"), key + "-ready");
        MvcResult assignment = assignDriver(flow.fulfillmentId(), warehouse, logistics,
                ready.getResponse().getHeader("ETag"), assignedMembership, key + "-assignment");
        String assignedEtag = assignment.getResponse().getHeader("ETag");
        MvcResult outgoing = recordMatchingOutgoingCheck(flow.fulfillmentId(), warehouse, assignedEtag,
                key + "-outgoing");
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + flow.fulfillmentId() + "/physical-allocation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk()).andReturn());
        String dispatchBody = "{\"physicalAllocationId\":\"" + allocation.get("allocationId").asText()
                + "\",\"physicalAllocationVersion\":" + allocation.get("version").asLong()
                + ",\"driverAssignmentId\":\"" + json(assignment).get("id").asText()
                + "\",\"driverAssignmentVersion\":" + json(assignment).get("fulfillmentVersion").asLong()
                + ",\"outgoingGoodsCheckId\":\"" + json(outgoing).get("id").asText() + "\"}";
        MvcResult dispatched = mockMvc.perform(post("/api/v1/fulfillments/" + flow.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + logistics)
                        .header("If-Match", assignedEtag)
                        .header("Idempotency-Key", key + "-dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk()).andReturn();
        UUID deliveryId = UUID.fromString(json(dispatched).get("deliveryId").asText());
        long version = tenantJdbc().queryForObject("select version from logistics.delivery where tenant_id=? and workspace_id=? and id=?",
                Long.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), deliveryId);
        return new DriverDeliveryFixture(deliveryId, version);
    }

    private String createOtherLogisticsDriver() {
        String email = "logistics-other-" + uuid().substring(0, 12) + "@icisa-test.local";
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID sourceMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID sourceUser = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, sourceMembership);
        jdbc.update("insert into iam.user_account(id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, email, email, "Other logistics driver");
        jdbc.update("insert into iam.password_credential(user_id,password_hash,algorithm,changed_at) "
                        + "select ?,password_hash,algorithm,current_timestamp from iam.password_credential where user_id=?",
                userId, sourceUser);
        jdbc.update("insert into tenant_management.workspace_membership(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'INTERNAL','ACTIVE',current_timestamp,current_timestamp,0)",
                membershipId, tenant, workspace, userId);
        jdbc.update("insert into tenant_management.membership_role_definition(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id,assigned_at) "
                        + "select ?,?,?,r.id,?,current_timestamp from tenant_management.role_definition r "
                        + "where r.code='logistics' and r.tenant_id is null and r.workspace_id is null",
                membershipId, tenant, workspace, sourceMembership);
        jdbc.update("insert into tenant_management.membership_authorization_state(membership_id,tenant_id,workspace_id,authorization_version,updated_at) "
                        + "values (?,?,?,0,current_timestamp) on conflict(membership_id) do nothing",
                membershipId, tenant, workspace);
        return email;
    }

    private void ensureActiveDriverWorkday(String logistics, String key) throws Exception {
        MvcResult current = mockMvc.perform(get("/api/v1/driver/workdays/current")
                        .header("Authorization", "Bearer " + logistics)).andReturn();
        if (current.getResponse().getStatus() == 204) {
            mockMvc.perform(post("/api/v1/driver/workdays")
                            .header("Authorization", "Bearer " + logistics)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                    .andExpect(status().isOk());
            return;
        }
        if ("LOCATION_UNAVAILABLE".equals(json(current).path("status").asText())) {
            mockMvc.perform(post("/api/v1/driver/workdays/" + json(current).get("id").asText()
                            + "/location-availability")
                            .header("Authorization", "Bearer " + logistics)
                            .header("If-Match", current.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", key + "-availability")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                    .andExpect(status().isOk());
        }
    }

    private MvcResult assignDriver(UUID fulfillmentId, String warehouse, String logistics, String fulfillmentEtag,
                                   UUID responsibleMembershipId, String key) throws Exception {
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + fulfillmentId + "/physical-allocation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk()).andReturn());
        String body = "{\"responsibleMembershipId\":\"" + responsibleMembershipId
                + "\",\"physicalAllocationId\":\"" + allocation.get("allocationId").asText()
                + "\",\"physicalAllocationVersion\":" + allocation.get("version").asLong() + "}";
        return mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/driver-assignments")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private record DriverDeliveryFixture(UUID deliveryId, long version) {
        private String etag() { return "\"" + version + "\""; }
    }

    private record AttemptOutcomeLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) { }

    @Override
    protected void ensureCommercialInventory() throws Exception {
        super.ensureCommercialInventory();
        // Commercial backing spans workspace warehouses. This matrix explicitly
        // grants its operator every eligible fixture warehouse, then supplies a
        // whole earliest-expiry lot in each to isolate single-line scan scenarios.
        // The separate split-line test constructs and verifies fragmented picks.
        var locations = tenantJdbc().query("select distinct on (w.id) w.id,z.id from warehouse.warehouse w "
                        + "join warehouse.storage_zone z on z.warehouse_id=w.id and z.tenant_id=w.tenant_id and z.workspace_id=w.workspace_id "
                        + "left join warehouse.warehouse_service_configuration service on service.warehouse_id=w.id and service.tenant_id=w.tenant_id and service.workspace_id=w.workspace_id "
                        + "where w.tenant_id=? and w.workspace_id=? and w.status='ACTIVE' and z.status='ACTIVE' "
                        + "and z.zone_type<>'QUARANTINE' and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' order by w.id,z.id",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)},
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String warehouseMembership = membershipId(WAREHOUSE_EMAIL);
        String logisticsMembership = membershipId(LOGISTICS_EMAIL);
        for (String[] location : locations) {
            ensureWarehouseGrant(location[0], warehouseMembership, owner);
            ensureWarehouseGrant(location[0], logisticsMembership, owner);
        }
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        for (String[] location : locations) {
            mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                            .header("Authorization", "Bearer " + warehouse).header("Idempotency-Key", "scan-fixture-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"warehouseId\":\"" + location[0] + "\",\"zoneId\":\"" + location[1]
                                    + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"SCAN-" + uuid()
                                    + "\",\"expirationDate\":\"" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
                                    + "\",\"quantity\":1000,\"unit\":\"UNIT\"}"))
                    .andExpect(status().isCreated());
        }
    }

    private void ensureWarehouseGrant(String warehouseId, String targetMembershipId, String owner) throws Exception {
        var grants = json(mockMvc.perform(get("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn());
        for (var grant : grants) {
            if (!targetMembershipId.equals(grant.path("membershipId").asText())) continue;
            if ("ACTIVE".equals(grant.path("status").asText())) return;
            mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                            .header("Authorization", "Bearer " + owner)
                            .header("If-Match", "\"" + grant.path("version").asLong() + "\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                    .andExpect(status().isOk());
            return;
        }
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                .andExpect(status().isOk());
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
        PhysicalLine physical = tenantJdbc().queryForObject("select l.id,l.sku_id,l.lot_id,l.warehouse_id,pa.version "
                        + "from warehouse.physical_allocation_line l join warehouse.physical_allocation pa "
                        + "on pa.tenant_id=l.tenant_id and pa.workspace_id=l.workspace_id and pa.id=l.physical_allocation_id "
                        + "where l.physical_allocation_id=(select physical_allocation_id from logistics.fulfillment where id=?)",
                (rs, row) -> new PhysicalLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getLong(5)), UUID.fromString(fulfillmentId));
        return new PhysicalFlow(UUID.fromString(fulfillmentId), UUID.fromString(lineId), physical.skuId(), physical.lotId(),
                physical.warehouseId(), physical.id(), physical.version(), pickingStart.getResponse().getHeader("ETag"),
                "mobile-picking-confirm-" + uuid());
    }

    private UUID createGrantedWarehouse() throws Exception {
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = uuid().replace("-", "").substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        MvcResult created = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-SCAN-" + suffix + "\",\"name\":\"Scan comparison warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID warehouseId = UUID.fromString(json(created).get("id").asText());
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-SCAN-" + suffix + "\",\"name\":\"Granted scan comparison zone\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated());
        return warehouseId;
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

    private UUID insertDuplicateBatchInAnotherWarehouse(PhysicalFlow flow, String batchNumber, UUID otherWarehouseId) {
        UUID zoneId = tenantJdbc().queryForObject("select id from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? order by id limit 1",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), otherWarehouseId);
        UUID id = UUID.randomUUID();
        assertThat(tenantJdbc().update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,version,sku_id) "
                        + "select ?,l.tenant_id,l.workspace_id,z.warehouse_id,z.id,l.catalog_item_id,?,l.expiration_date,l.received_at,0,0,l.unit,'AVAILABLE',l.temperature_range_snapshot,0,l.sku_id "
                        + "from warehouse.inventory_lot l join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.id=? and z.id=? and z.warehouse_id<>l.warehouse_id",
                id, batchNumber, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), flow.lotId(), zoneId)).isEqualTo(1);
        return id;
    }

    private UUID insertAlternativeLot(PhysicalFlow flow, String batchNumber, String quantity) {
        UUID id = UUID.randomUUID();
        tenantJdbc().update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,version,sku_id) "
                        + "select ?,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,?,current_date+365,current_timestamp,?,0,unit,'AVAILABLE',temperature_range_snapshot,0,sku_id "
                        + "from warehouse.inventory_lot where id=?",
                id, batchNumber, new BigDecimal(quantity), flow.lotId());
        return id;
    }

    private PhysicalLine splitAllocation(PhysicalFlow flow, UUID secondLot) {
        tenantJdbc().update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-1,version=version+1 where id=? and reserved_quantity>=1",
                flow.lotId());
        tenantJdbc().update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity+1,version=version+1 where id=?",
                secondLot);
        tenantJdbc().update("update warehouse.physical_allocation_line set quantity=quantity-1 where id=? and quantity>=1",
                flow.physicalAllocationLineId());
        UUID secondLine = UUID.randomUUID();
        tenantJdbc().update("insert into warehouse.physical_allocation_line(id,tenant_id,workspace_id,physical_allocation_id,sku_id,catalog_item_id,warehouse_id,zone_id,lot_id,quantity,released_quantity,consumed_quantity,unit,expiration_date,created_at) "
                        + "select ?,tenant_id,workspace_id,physical_allocation_id,sku_id,catalog_item_id,warehouse_id,zone_id,?,1,0,0,unit,"
                        + "(select expiration_date from warehouse.inventory_lot where id=?),current_timestamp "
                        + "from warehouse.physical_allocation_line where id=?",
                secondLine, secondLot, secondLot, flow.physicalAllocationLineId());
        return tenantJdbc().queryForObject("select id,sku_id,lot_id,warehouse_id,? from warehouse.physical_allocation_line where id=?",
                (rs, row) -> new PhysicalLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getLong(5)),
                flow.allocationVersion(), secondLine);
    }

    private static String pickingBody(PhysicalFlow flow, String quantity) {
        return "{\"allocationVersion\":" + flow.allocationVersion() + ",\"lines\":["
                + pickingLineBody(flow.fulfillmentLineId(), flow.skuId(), quantity, flow.physicalAllocationLineId(), flow.lotId(), flow.warehouseId())
                + "]}";
    }

    private static String splitPickingBody(PhysicalFlow first, PhysicalLine second) {
        return "{\"allocationVersion\":" + first.allocationVersion() + ",\"lines\":["
                + pickingLineBody(first.fulfillmentLineId(), first.skuId(), "1", first.physicalAllocationLineId(), first.lotId(), first.warehouseId())
                + "," + pickingLineBody(first.fulfillmentLineId(), second.skuId(), "1", second.id(), second.lotId(), second.warehouseId())
                + "]}";
    }

    private static String pickingLineBody(UUID fulfillmentLineId, UUID skuId, String quantity,
                                          UUID physicalAllocationLineId, UUID lotId, UUID warehouseId) {
        return "{\"fulfillmentLineId\":\"" + fulfillmentLineId + "\",\"skuId\":\"" + skuId
                + "\",\"quantity\":" + quantity + ",\"unit\":\"UNIT\",\"physicalAllocationLineId\":\""
                + physicalAllocationLineId + "\",\"lotId\":\"" + lotId + "\",\"warehouseId\":\""
                + warehouseId + "\"}";
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
        return tenantJdbc().queryForObject("select stock_quantity,reserved_quantity from warehouse.inventory_lot where id=?",
                (rs, row) -> new StockSnapshot(rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity")), lotId);
    }

    private MvcResult recordMatchingOutgoingCheck(UUID fulfillmentId, String warehouseToken,
                                                   String fulfillmentEtag, String idempotencyKey) throws Exception {
        var allocation = json(mockMvc.perform(get("/api/v1/fulfillments/" + fulfillmentId + "/physical-allocation")
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isOk()).andReturn());
        StringBuilder body = new StringBuilder("{\"physicalAllocationId\":\"")
                .append(allocation.get("allocationId").asText())
                .append("\",\"physicalAllocationVersion\":").append(allocation.get("version").asLong())
                .append(",\"observations\":[");
        for (int index = 0; index < allocation.get("lines").size(); index++) {
            var line = allocation.get("lines").get(index);
            if (index > 0) body.append(',');
            BigDecimal quantity = line.get("remainingQuantity").decimalValue();
            body.append("{\"physicalAllocationLineId\":\"").append(line.get("physicalAllocationLineId").asText())
                    .append("\",\"observedLotId\":")
                    .append(quantity.signum() == 0 ? "null" : "\"" + line.get("lotId").asText() + "\"")
                    .append(",\"observedQuantity\":").append(quantity.toPlainString()).append('}');
        }
        body.append("]}");
        return mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/outgoing-checks")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn();
    }

    private record PhysicalLine(UUID id, UUID skuId, UUID lotId, UUID warehouseId, long version) { }
    private record StockSnapshot(BigDecimal stock, BigDecimal reserved) { }

    private record PhysicalFlow(UUID fulfillmentId, UUID fulfillmentLineId, UUID skuId, UUID lotId, UUID warehouseId,
                                UUID physicalAllocationLineId, long allocationVersion, String pickingEtag, String pickingKey) { }
}
