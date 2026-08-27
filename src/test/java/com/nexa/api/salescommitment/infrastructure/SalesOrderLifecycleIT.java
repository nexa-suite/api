package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

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

    @Test void cancellationAfterSuccessfulPaymentPostsDurableAdjustmentAndObligation() throws Exception {
        var order = createConfirmedSalesOrder();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult receivableResult = mockMvc.perform(post("/api/v1/receivables")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "cancel-receivable-" + UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"SALES_ORDER\",\"subjectId\":\"" + order.id() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID receivableId = UUID.fromString(json(receivableResult).get("id").asText());
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult transfer = mockMvc.perform(post("/api/v1/receivables/" + receivableId + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "cancel-payment-" + UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BT-CANCEL-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentId = UUID.fromString(json(transfer).get("id").asText());
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/approve")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "cancel-approve-" + UUID.randomUUID()))
                .andExpect(status().isOk());

        MvcResult cancelled = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/cancellations")
                        .header("Authorization", "Bearer " + order.salesToken())
                        .header("If-Match", order.etag()))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from payments.payment where id=?", String.class, paymentId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from payments.financial_adjustment where tenant_id=? and workspace_id=? and source_type='SALES_ORDER_CANCELLATION' and source_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments.refund_credit_obligation where tenant_id=? and workspace_id=? and sales_order_id=? and status='OPEN' and obligation_type='CUSTOMER_CREDIT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select adjustment_total from payments.receivable where id=?", BigDecimal.class, receivableId))
                .isEqualByComparingTo(json(receivableResult).get("amount").decimalValue().negate());
    }
}
