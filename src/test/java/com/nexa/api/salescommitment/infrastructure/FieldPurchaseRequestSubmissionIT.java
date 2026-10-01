package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class FieldPurchaseRequestSubmissionIT extends NexaWorkflowIntegrationSupport {
    @Test
    void sameScopedIntentSubmitsOneRequestAndChangedBodyCannotReuseIt() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        BigDecimal price = price(buyer);
        String key = "field-" + uuid();
        String command = command(price, 1);
        long before = requestCount();
        MvcResult first = mockMvc.perform(post("/api/v1/purchase-requests/field-submissions")
                .header("Authorization", "Bearer " + sales).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/purchase-requests/field-submissions")
                .header("Authorization", "Bearer " + sales).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(json(first).get("id").asText());
        assertThat(requestCount()).isEqualTo(before + 1);
        mockMvc.perform(post("/api/v1/purchase-requests/field-submissions")
                .header("Authorization", "Bearer " + sales).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(command(price, 2)))
                .andExpect(status().isConflict());
        assertThat(requestCount()).isEqualTo(before + 1);
    }
    @Test
    void stalePriceAndBuyerAuthorityCannotLeavePartialCommitment() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        BigDecimal price = price(buyer);
        long before = requestCount();
        mockMvc.perform(post("/api/v1/purchase-requests/field-submissions")
                .header("Authorization", "Bearer " + sales).header("Idempotency-Key", "stale-" + uuid())
                .contentType(MediaType.APPLICATION_JSON).content(command(price.add(BigDecimal.ONE), 1)))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/v1/purchase-requests/field-submissions")
                .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "buyer-" + uuid())
                .contentType(MediaType.APPLICATION_JSON).content(command(price, 1)))
                .andExpect(status().isForbidden());
        assertThat(requestCount()).isEqualTo(before);
    }
    private BigDecimal price(String buyer) throws Exception {
        var response = mockMvc.perform(get("/api/v1/catalog-items/CAT-0002")
                .header("Authorization", "Bearer " + buyer)).andExpect(status().isOk()).andReturn();
        return new BigDecimal(json(response).get("unitPrice").get("amount").asText());
    }
    private long requestCount() {
        return jdbc.queryForObject("select count(*) from sales.purchase_request where tenant_id=? and workspace_id=? and client_account_id=?",
                Long.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(buyerClientAccountId()));
    }
    private String command(BigDecimal price, int quantity) throws Exception {
        return "{\"clientAccountId\":\"" + buyerClientAccountId() + "\",\"paymentOption\":\"CASH_ON_DELIVERY\",\"requestedDeliveryDate\":\"2099-12-31\","
                + "\"deliveryProfileSnapshot\":\"Authorized field request\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":" + quantity
                + ",\"unit\":\"UNIT\",\"expectedUnitPrice\":" + price.toPlainString() + ",\"expectedCurrency\":\"PEN\"}]}";
    }
}
