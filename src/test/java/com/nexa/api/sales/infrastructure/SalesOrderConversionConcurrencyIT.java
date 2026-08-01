package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOrderConversionConcurrencyIT extends NexaWorkflowIntegrationSupport {
    @Test void sameConversionKeyProducesOneSalesOrderAndOneIdempotencyRecord() throws Exception {
        var request = createApprovedPurchaseRequest();
        String key = "conversion-concurrent-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> conversionStatus(request, key));
            var second = executor.submit(() -> conversionStatus(request, key));
            assertThat(java.util.List.of(first.get(), second.get())).allMatch(value -> value == 201 || value == 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, UUID.fromString(request.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales.idempotency_record where actor_membership_id=? and operation='purchase-request-order-conversion' and idempotency_key=?", Integer.class, java.util.UUID.fromString(membershipId(SALES_EMAIL)), key)).isEqualTo(1);
    }

    private int conversionStatus(PurchaseRequestResource request, String key) {
        try {
            return mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                            .header("Authorization", "Bearer " + request.salesToken()).header("If-Match", request.etag()).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
