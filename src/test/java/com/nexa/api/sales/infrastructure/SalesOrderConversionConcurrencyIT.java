package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOrderConversionConcurrencyIT extends NexaWorkflowIntegrationSupport {
    @Test void sameConversionKeyProducesOneSalesOrderAndOneIdempotencyRecord() throws Exception {
        var request = createApprovedPurchaseRequest();
        String key = "conversion-concurrent-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> conversionResult(request, key));
            var second = executor.submit(() -> conversionResult(request, key));
            var results = List.of(first.get(), second.get());
            assertThat(results).allMatch(value -> value.status() == 201);
            assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
        }
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, UUID.fromString(request.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales.idempotency_record where actor_membership_id=? and operation='purchase-request-order-conversion' and idempotency_key=?", Integer.class, java.util.UUID.fromString(membershipId(SALES_EMAIL)), key)).isEqualTo(1);
    }

    @Test void sameKeyWithDifferentPayloadReturnsIdempotencyConflict() throws Exception {
        var request = createApprovedPurchaseRequest();
        String key = "conversion-conflict-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + request.salesToken()).header("If-Match", request.etag()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"first\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + request.salesToken()).header("If-Match", request.etag()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"second\"}"))
                .andExpect(status().isConflict());
    }

    private ConversionResult conversionResult(PurchaseRequestResource request, String key) {
        try {
            var result = mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                            .header("Authorization", "Bearer " + request.salesToken()).header("If-Match", request.etag()).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
            return new ConversionResult(result.getResponse().getStatus(), json(result).get("id").asText());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record ConversionResult(int status, String id) { }
}
