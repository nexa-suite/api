package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ManualSalesOrderConcurrencyIT extends NexaWorkflowIntegrationSupport {
    @Test
    void concurrentManualOrderRequestsCreateOneOrderAndOneIdempotencyClaim() throws Exception {
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String key = "manual-concurrent-" + uuid();
        String body = "{\"clientAccountId\":\"" + buyerClientAccountId() + "\",\"requestedDeliveryDate\":\"2099-12-31\","
                + "\"manualAddress\":{\"addressType\":\"STREET\",\"line\":\"Av. Lima 123\",\"countryCode\":\"PE\","
                + "\"departmentCode\":\"15\",\"provinceCode\":\"1501\",\"districtCode\":\"150101\"},"
                + "\"paymentOption\":\"CASH\",\"priority\":\"NORMAL\",\"currency\":\"PEN\","
                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}";
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(sales, key, body));
            var second = executor.submit(() -> create(sales, key, body));
            List<MvcResult> results = List.of(first.get(), second.get());
            assertThat(results).allMatch(result -> result.getResponse().getStatus() == 201);
            assertThat(json(results.get(0)).get("id").asText()).isEqualTo(json(results.get(1)).get("id").asText());
        }
        String tenant = tenantId();
        String workspace = workspaceId();
        String actor = membershipId(SALES_EMAIL);
        assertThat(jdbc.queryForObject("select count(*) from sales.manual_order_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?", Integer.class,
                java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(actor), key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where tenant_id=? and workspace_id=? and created_by_membership_id=? and order_source='MANUAL' and number in (select o.number from sales.sales_order o join sales.manual_order_idempotency i on i.sales_order_id=o.id where i.tenant_id=? and i.workspace_id=? and i.actor_membership_id=? and i.idempotency_key=?)", Integer.class,
                java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(actor),
                java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(actor), key)).isEqualTo(1);
    }

    private MvcResult create(String token, String key, String body) {
        try {
            return mockMvc.perform(post("/api/v1/sales-orders/manual")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
