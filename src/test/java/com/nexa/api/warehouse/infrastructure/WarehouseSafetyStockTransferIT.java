package com.nexa.api.warehouse.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.sales.application.port.CommercialCommitmentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseSafetyStockTransferIT extends PostgresIntegrationSupport {

    /** The checkout contains a final, transactional WIP adapter; replace only that unrelated port in this test context. */
    @MockitoBean
    CommercialCommitmentPort commercialCommitmentPort;

    @Test
    void safetyStockIsVersionedAndExcludedFromSellableAvailability() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        WarehouseLocation source = warehouse(token, "WH-SS-" + suffix, "Safety stock warehouse");
        zone(token, source.warehouseId(), "Z-SS-" + suffix, "Ambient");
        MvcResult baselineAvailability = mockMvc.perform(get("/api/v1/inventory-availability")
                        .header("Authorization", "Bearer " + token)
                        .param("catalogItemId", "CAT-0002"))
                .andExpect(status().isOk()).andReturn();
        MvcResult receipt = receive(token, source, "B-SS-" + suffix, "10", "ss-receipt-" + suffix);
        String lotEtag = receipt.getResponse().getHeader("ETag");

        String policyBody = "{\"warehouseId\":\"" + source.warehouseId()
                + "\",\"catalogItemId\":\"CAT-0002\",\"quantity\":\"2\",\"unit\":\"UNIT\"}";
        MvcResult policy = mockMvc.perform(put("/api/v1/inventory/safety-stocks")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "ss-policy-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(policyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        MvcResult availability = mockMvc.perform(get("/api/v1/inventory-availability")
                        .header("Authorization", "Bearer " + token)
                        .param("catalogItemId", "CAT-0002"))
                .andExpect(status().isOk()).andReturn();
        var availabilityRow = json(availability).get(0);
        var baselineRow = json(baselineAvailability).get(0);
        assertThat(availabilityRow.get("status").asText()).isEqualTo("AVAILABLE");
        BigDecimal baselinePhysicalQuantity = new BigDecimal(baselineRow.get("physicalQuantity").asText());
        BigDecimal baselineSafetyStock = new BigDecimal(baselineRow.get("safetyStock").asText());
        BigDecimal baselineSellableQuantity = new BigDecimal(baselineRow.get("sellableQuantity").asText());
        BigDecimal physicalQuantity = new BigDecimal(availabilityRow.get("physicalQuantity").asText());
        BigDecimal safetyStock = new BigDecimal(availabilityRow.get("safetyStock").asText());
        BigDecimal sellableQuantity = new BigDecimal(availabilityRow.get("sellableQuantity").asText());
        assertThat(physicalQuantity.subtract(baselinePhysicalQuantity)).isEqualByComparingTo("10");
        assertThat(safetyStock.subtract(baselineSafetyStock)).isEqualByComparingTo("2");
        assertThat(sellableQuantity.subtract(baselineSellableQuantity)).isEqualByComparingTo("8");

        String policyEtag = policy.getResponse().getHeader("ETag");
        mockMvc.perform(put("/api/v1/inventory/safety-stocks")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", policyEtag)
                        .header("Idempotency-Key", "ss-policy-update-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody.replace("\"2\"", "\"3\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/v1/inventory/safety-stocks")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", policyEtag)
                        .header("Idempotency-Key", "ss-policy-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody))
                .andExpect(status().isPreconditionFailed());

        WarehouseLocation destination = warehouse(token, "WH-SS-D-" + suffix, "Safety stock destination");
        String destinationZone = zone(token, destination.warehouseId(), "Z-SS-D-" + suffix, "Ambient");
        String lotId = json(receipt).get("id").asText();
        String transferBody = "{\"sourceLotId\":\"" + lotId + "\",\"sourceWarehouseId\":\""
                + source.warehouseId() + "\",\"destinationWarehouseId\":\"" + destination.warehouseId()
                + "\",\"destinationZoneId\":\"" + destinationZone + "\",\"quantity\":\"8\","
                + "\"unit\":\"UNIT\",\"reason\":\"Safety stock must remain protected\"}";
        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", lotEtag)
                        .header("Idempotency-Key", "ss-transfer-protected-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_SAFETY_STOCK_PROTECTED"));
    }

    @Test
    void partialTransferIsAtomicAndIdempotent() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        WarehouseLocation source = warehouse(token, "WH-TR-" + suffix, "Transfer source");
        String sourceZone = zone(token, source.warehouseId(), "Z-TR-" + suffix, "Ambient");
        WarehouseLocation destination = warehouse(token, "WH-TR-D-" + suffix, "Transfer destination");
        String destinationZone = zone(token, destination.warehouseId(), "Z-TR-D-" + suffix, "Ambient");
        MvcResult receipt = receive(token, source, "B-TR-" + suffix, "10", "tr-receipt-" + suffix);
        String lotId = json(receipt).get("id").asText();
        String lotEtag = receipt.getResponse().getHeader("ETag");
        String body = "{\"sourceLotId\":\"" + lotId + "\",\"sourceWarehouseId\":\""
                + source.warehouseId() + "\",\"sourceZoneId\":\"" + sourceZone
                + "\",\"destinationWarehouseId\":\"" + destination.warehouseId()
                + "\",\"destinationZoneId\":\"" + destinationZone
                + "\",\"quantity\":\"4\",\"unit\":\"UNIT\",\"reason\":\"Rebalance cold-chain stock\"}";
        String key = "tr-partial-" + suffix;

        MvcResult created = mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", lotEtag).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("PARTIAL"))
                .andExpect(jsonPath("$.requestedQuantity").value(4))
                .andExpect(jsonPath("$.transferredQuantity").value(4))
                .andExpect(jsonPath("$.sourceVersionBefore").value(0))
                .andExpect(jsonPath("$.sourceVersionAfter").value(1))
                .andReturn();
        String transferId = json(created).get("id").asText();

        MvcResult replay = mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", lotEtag).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(transferId);

        assertThat(stock(lotId)).isEqualByComparingTo("6");
        UUID destinationLotId = jdbc.queryForObject("select id from warehouse.inventory_lot where tenant_id=? and workspace_id=?"
                        + " and warehouse_id=? and zone_id=? and batch_number=?",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(destination.warehouseId()),
                UUID.fromString(destinationZone), "B-TR-" + suffix);
        assertThat(stock(destinationLotId.toString())).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_transfer where id=?", Integer.class,
                UUID.fromString(transferId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=? and movement_type='TRANSFER_OUT'",
                Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=? and movement_type='TRANSFER_IN'",
                Integer.class, destinationLotId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", lotEtag).header("Idempotency-Key", "tr-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"4\"", "\"1\"")))
                .andExpect(status().isPreconditionFailed());
    }

    private WarehouseLocation warehouse(String token, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return new WarehouseLocation(json(result).get("id").asText());
    }

    private String zone(String token, String warehouseId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("id").asText();
    }

    private MvcResult receive(String token, WarehouseLocation warehouse, String batch, String quantity, String key) throws Exception {
        String zoneId = jdbc.queryForObject("select id::text from warehouse.storage_zone where warehouse_id=? and code like 'Z-%' order by created_at desc limit 1",
                String.class, UUID.fromString(warehouse.warehouseId()));
        return mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouse.warehouseId() + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"" + batch
                                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"" + quantity
                                + "\",\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private BigDecimal stock(String lotId) {
        return jdbc.queryForObject("select stock_quantity from warehouse.inventory_lot where id=?", BigDecimal.class,
                UUID.fromString(lotId));
    }

    private static tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record WarehouseLocation(String warehouseId) { }
}
