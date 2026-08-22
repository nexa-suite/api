package com.nexa.api.warehouse.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseApiIntegrationTests extends PostgresIntegrationSupport {
    @Test void buyerWarehouseProjectionDoesNotWriteInsideReadOnlyRequest() throws Exception {
        String token = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/buyer/warehouses").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test void warehouseZoneAndInboundReceiptAreScopedAndLedgered() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WH-"+suffix+"\",\"name\":\"Test Warehouse\",\"address\":\"Lima\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Z-"+suffix+"\",\"name\":\"Ambient\",\"type\":\"AMBIENT\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        String receiptRequest = "{\"warehouseId\":\""+warehouseId+"\",\"zoneId\":\""+zoneId+"\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"B-001\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\"}";
        String receipt = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token).header("Idempotency-Key", "inbound-test-1").contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.available").value(10)).andReturn().getResponse().getContentAsString();
        String lotId = tools.jackson.databind.json.JsonMapper.shared().readTree(receipt).get("id").asText();
        String replay = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token).header("Idempotency-Key", "inbound-test-1").contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(tools.jackson.databind.json.JsonMapper.shared().readTree(replay).get("id").asText()).isEqualTo(lotId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, java.util.UUID.fromString(lotId))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_event where aggregate_id=? and event_type='warehouse.lot.received'", Integer.class, java.util.UUID.fromString(lotId))).isEqualTo(1);
    }

    @Test void outOfRangeReceiptIsHeldUntilExplicitDisposition() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-H-"+suffix+"\",\"name\":\"Hold Warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"C-"+suffix+"\",\"name\":\"Chilled QA\",\"type\":\"CHILLED\",\"temperatureMin\":-5,\"temperatureMax\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        String receiptRequest = "{\"warehouseId\":\""+warehouseId+"\",\"zoneId\":\""+zoneId+"\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"H-"+suffix+"\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\",\"temperatureReading\":10}";
        MvcResult receipt = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token)
                        .header("Idempotency-Key", "hold-receipt-"+suffix).contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("HOLD")).andReturn();
        String lotId = tools.jackson.databind.json.JsonMapper.shared().readTree(receipt.getResponse().getContentAsString()).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("OPEN");
        String etag = receipt.getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/inventory/lots/"+lotId+"/dispositions").header("Authorization", "Bearer "+token)
                        .header("If-Match", etag).header("Idempotency-Key", "hold-disposition-"+suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disposition\":\"RELEASE\",\"reason\":\"QA cleared\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select disposition from warehouse.inventory_lot_disposition where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("RELEASE");
    }
}
