package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseInventoryPagingIT extends NexaWorkflowIntegrationSupport {

    @Test
    void receivingPreservesProductionAndEntryDatesAndRejectsInvalidChronology() throws Exception {
        ensureCommercialInventory();
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        var inventory = mockMvc.perform(get("/api/v1/inventory").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var lot = json(inventory).get("items").get(0);
        String batch = "DATES-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String entered = java.time.Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String produced = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2).toString();
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("warehouseId", lot.get("warehouseId").asText());
        payload.put("zoneId", lot.get("zoneId").asText());
        payload.put("catalogItemId", lot.get("catalogItemId").asText());
        payload.put("skuId", lot.get("skuId").asText());
        payload.put("batchNumber", batch);
        payload.put("expirationDate", "2099-01-01");
        payload.put("productionDate", produced);
        payload.put("receivedAt", entered);
        payload.put("quantity", 2);
        payload.put("unit", lot.get("unit").asText());
        java.math.BigDecimal temperature = tenantJdbc().queryForObject("select coalesce(greatest(s.temperature_min,z.temperature_min),least(s.temperature_max,z.temperature_max),20) from warehouse.storage_zone z join catalog_management.sellable_sku s on s.tenant_id=z.tenant_id and s.workspace_id=z.workspace_id where z.id=? and s.id=?",
                java.math.BigDecimal.class, java.util.UUID.fromString(lot.get("zoneId").asText()), java.util.UUID.fromString(lot.get("skuId").asText()));
        payload.put("temperatureReading", temperature);
        String key = "receipt-dates-" + java.util.UUID.randomUUID();
        String body = tools.jackson.databind.json.JsonMapper.shared().writeValueAsString(payload);
        var received = mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.productionDate").value(produced))
                .andExpect(jsonPath("$.receivedAt").value(entered)).andReturn();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(json(received).get("id").asText()));
        payload.put("productionDate", "2099-01-02");
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key + "-invalid")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(tools.jackson.databind.json.JsonMapper.shared().writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsInventoryPageAndCountsRowsWithTemperatureHoldProjection() throws Exception {
        ensureCommercialInventory();
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        var response = mockMvc.perform(get("/api/v1/inventory")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(json(response).get("items")).isNotEmpty();
        assertThat(json(response).get("total").asLong()).isPositive();
    }
}
