package com.nexa.api.warehouse.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseApiIntegrationTests extends PostgresIntegrationSupport {
    @Test void warehouseZoneAndInboundReceiptAreScopedAndLedgered() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WH-TEST\",\"name\":\"Test Warehouse\",\"address\":\"Lima\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Z-TEST\",\"name\":\"Ambient\",\"type\":\"AMBIENT\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token).header("Idempotency-Key", "inbound-test-1").contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\":\""+warehouseId+"\",\"zoneId\":\""+zoneId+"\",\"catalogItemId\":\"CAT-001\",\"batchNumber\":\"B-001\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.available").value(10));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where catalog_item_id='CAT-001'", Integer.class)).isEqualTo(1);
    }
}
