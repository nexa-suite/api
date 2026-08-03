package com.nexa.api.shared.presentation;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OpenApiContractIT extends NexaWorkflowIntegrationSupport {
    @Test void runtimeOpenApiContainsWarehouseAndLogisticsContracts() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = json(result);
        assertThat(document.get("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.get("paths").has("/api/v1/warehouses/{warehouseId}/zones/{zoneId}")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/profile")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/location")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/hours")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/serviceability")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/selection-policy")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/warehouses")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/route-starts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/handoff-notes")).isTrue();
        assertThat(document.get("paths").has("/api/v1/my-deliveries/{id}/events")).isTrue();
    }
}
