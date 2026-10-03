package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseInventoryPagingIT extends NexaWorkflowIntegrationSupport {

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
