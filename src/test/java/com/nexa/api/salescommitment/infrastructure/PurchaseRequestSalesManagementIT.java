package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestSalesManagementIT extends NexaWorkflowIntegrationSupport {
    @Test
    void salesCanCreateAndSubmitInternalPurchaseRequestForActiveClientAccount() throws Exception {
        ensureCommercialInventory();
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
        String submitKey = "sales-submit-" + uuid();

        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + id + "/submissions")
                .header("Authorization", "Bearer " + token)
                .header("If-Match", etag)
                .header("Idempotency-Key", submitKey))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();

        mockMvc.perform(post("/api/v1/purchase-requests/" + id + "/submissions")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        String cancelKey = "sales-cancel-" + uuid();
        mockMvc.perform(post("/api/v1/purchase-requests/" + id + "/cancellations")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", cancelKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/purchase-requests/" + id + "/cancellations")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", cancelKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        UUID requestId = UUID.fromString(id);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from sales.commercial_commitment where purchase_request_id=? and status='RELEASED'",
                Integer.class, requestId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from warehouse.inventory_backing b join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "where c.purchase_request_id=? and b.status='RELEASED'",
                Integer.class, requestId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from payments.credit_reservation where purchase_request_id=? and status='RELEASED'",
                Integer.class, requestId)).isEqualTo(1);

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from payments.credit_account where tenant_id=?::uuid and workspace_id=?::uuid "
                        + "and client_account_id=?::uuid and currency='PEN' and status='ACTIVE'",
                Integer.class, tenantId(), workspaceId(), buyerClientAccountId())).isEqualTo(1);
    }
}
