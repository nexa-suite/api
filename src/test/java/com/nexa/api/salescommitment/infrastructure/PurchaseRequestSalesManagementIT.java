package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestSalesManagementIT extends NexaWorkflowIntegrationSupport {
    @Test
    void buyerAcceptanceRevalidatesAndAtomicallyReplacesPurchaseRequestBacking() throws Exception {
        ensureCommercialInventory();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Material change test\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}] }"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        UUID request = UUID.fromString(requestId);
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-submit-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();

        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String proposalKey = "material-proposal-" + uuid();
        String proposalBody = "{\"reason\":\"Buyer-requested quantity change\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":2,\"unit\":\"UNIT\"}]}";
        MvcResult proposed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", proposalKey)
                        .contentType(MediaType.APPLICATION_JSON).content(proposalBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.proposedTerms.lines[0].quantity").value(2)).andReturn();
        String proposalId = json(proposed).get("id").asText();
        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", proposalKey)
                        .contentType(MediaType.APPLICATION_JSON).content(proposalBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(proposalId));

        mockMvc.perform(get("/api/v1/purchase-requests/" + requestId + "/material-changes/current")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(proposalId))
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes/" + proposalId + "/acceptances")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", proposed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-accept-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));

        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request_line where purchase_request_id=?", Integer.class, request)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request_line where purchase_request_id=? and superseded_at is null", Integer.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(quantity) from sales.purchase_request_line where purchase_request_id=? and superseded_at is null", java.math.BigDecimal.class, request)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where purchase_request_id=?", String.class, request)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_backing where commercial_commitment_id=(select id from sales.commercial_commitment where purchase_request_id=?)", String.class, request)).isEqualTo("BACKED");
        assertThat(jdbc.queryForObject("select sum(requested_quantity) from warehouse.inventory_backing_line where backing_id=(select b.id from warehouse.inventory_backing b join sales.commercial_commitment c on c.id=b.commercial_commitment_id where c.purchase_request_id=?)", java.math.BigDecimal.class, request)).isEqualByComparingTo("2");
        java.math.BigDecimal requestTotal = jdbc.queryForObject("select sum(quantity*unit_price_amount) from sales.purchase_request_line where purchase_request_id=? and superseded_at is null", java.math.BigDecimal.class, request);
        java.math.BigDecimal reservedTotal = jdbc.queryForObject("select amount from payments.credit_reservation where purchase_request_id=? and status='RESERVED'", java.math.BigDecimal.class, request);
        assertThat(reservedTotal).isEqualByComparingTo(requestTotal);
        assertThat(jdbc.queryForObject("select status from sales.purchase_request_material_change where id=?", String.class, UUID.fromString(proposalId))).isEqualTo("ACCEPTED");
    }

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
