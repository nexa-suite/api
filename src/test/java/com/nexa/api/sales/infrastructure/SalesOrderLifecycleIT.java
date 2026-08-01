package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOrderLifecycleIT extends NexaWorkflowIntegrationSupport {
    @Test void confirmsThroughDomainBackedApplicationFlowWithEtag() throws Exception {
        var order = createSalesOrder();
        var result = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/confirmations")
                        .header("Authorization", "Bearer " + order.salesToken()).header("If-Match", order.etag()))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(result).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    }
}
