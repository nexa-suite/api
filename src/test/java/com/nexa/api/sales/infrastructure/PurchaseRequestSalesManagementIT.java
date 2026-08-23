package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestSalesManagementIT extends NexaWorkflowIntegrationSupport {
    @Test
    void salesCanCreateAndSubmitInternalPurchaseRequestForActiveClientAccount() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "sales-create-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId() + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Sales internal delivery snapshot\",\"paymentOption\":\"CREDIT_LINE\",\"comment\":\"Sales management flow\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]" + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = json(created).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");

        mockMvc.perform(post("/api/v1/purchase-requests/" + id + "/submissions")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "sales-submit-" + uuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from payments.credit_account where tenant_id=?::uuid and workspace_id=?::uuid "
                        + "and client_account_id=?::uuid and currency='PEN' and status='ACTIVE'",
                Integer.class, tenantId(), workspaceId(), buyerClientAccountId())).isEqualTo(1);
    }
}
