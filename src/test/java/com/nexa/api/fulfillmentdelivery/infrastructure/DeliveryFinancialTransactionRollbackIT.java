package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controlled transaction failure seam before final financial adjustment. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DeliveryFinancialTransactionRollbackIT extends NexaWorkflowIntegrationSupport {

    @MockitoBean
    FinancialAdjustmentCommands financialAdjustments;

    @Test
    void failureBeforeFinalFinancialAdjustmentRollsBackDeliveryResolutionAndOutbox() throws Exception {
        SalesOrderResource order = createConfirmedDirectOrder();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");

        MvcResult allocated = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.etag())
                        .header("Idempotency-Key", "rollback-financial-start-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        String fulfillmentId = json(allocated).get("id").asText();
        String fulfillmentEtag = allocated.getResponse().getHeader("ETag");
        String lineId = json(allocated).get("lines").get(0).get("id").asText();
        String skuId = json(allocated).get("lines").get(0).get("skuId").asText();

        MvcResult picking = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-starts")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", "rollback-financial-picking-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
        fulfillmentEtag = picking.getResponse().getHeader("ETag");
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/picking-confirmations")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", "rollback-financial-pick-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"fulfillmentLineId\":\"" + lineId
                                + "\",\"skuId\":\"" + skuId + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andReturn();
        fulfillmentEtag = picked.getResponse().getHeader("ETag");
        MvcResult packed = transition(fulfillmentId, fulfillmentEtag, warehouse, "packing", "rollback-financial-pack-");
        fulfillmentEtag = packed.getResponse().getHeader("ETag");
        MvcResult staged = transition(fulfillmentId, fulfillmentEtag, warehouse, "staging", "rollback-financial-stage-");
        fulfillmentEtag = staged.getResponse().getHeader("ETag");
        MvcResult ready = transition(fulfillmentId, fulfillmentEtag, warehouse, "ready-for-dispatch", "rollback-financial-ready-");
        fulfillmentEtag = ready.getResponse().getHeader("ETag");
        MvcResult handedOver = transition(fulfillmentId, fulfillmentEtag, warehouse, "dispatches", "rollback-financial-dispatch-");
        String deliveryId = json(handedOver).get("deliveryId").asText();

        MvcResult delivery = mockMvc.perform(get("/api/v1/deliveries/" + deliveryId)
                        .header("Authorization", "Bearer " + logistics))
                .andExpect(status().isOk()).andReturn();
        MvcResult transit = mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/transit-starts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", delivery.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "rollback-financial-transit-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
        String deliveryEtag = transit.getResponse().getHeader("ETag");
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID deliveryUuid = UUID.fromString(deliveryId);
        UUID fulfillmentUuid = UUID.fromString(fulfillmentId);
        int attemptsBefore = jdbc.queryForObject("select count(*) from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=?",
                Integer.class, tenant, workspace, deliveryUuid);
        String deliveryStatusBefore = jdbc.queryForObject("select status from logistics.delivery where id=?", String.class, deliveryUuid);
        BigDecimal unresolvedBefore = jdbc.queryForObject("select delivered_quantity + rejected_quantity + cancelled_quantity from logistics.fulfillment_line where fulfillment_id=?",
                BigDecimal.class, fulfillmentUuid);
        int outboxBefore = jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=? and workspace_id=? and aggregate_id=?",
                Integer.class, tenant, workspace, deliveryUuid);

        doThrow(new IllegalStateException("failure-injection-before-financial-adjustment"))
                .when(financialAdjustments).postFinalQuantityAdjustment(any(FinancialAdjustmentCommands.Request.class));

        mockMvc.perform(post("/api/v1/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", deliveryEtag)
                        .header("Idempotency-Key", "rollback-financial-attempt-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"REFUSED\",\"failureReason\":\"Buyer refused\",\"lines\":[{\"fulfillmentLineId\":\""
                                + lineId + "\",\"skuId\":\"" + skuId
                                + "\",\"attemptedQuantity\":1,\"deliveredQuantity\":0,\"rejectedQuantity\":1,\"cancelledQuantity\":0,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isInternalServerError());

        verify(financialAdjustments).postFinalQuantityAdjustment(any(FinancialAdjustmentCommands.Request.class));
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=?",
                Integer.class, tenant, workspace, deliveryUuid)).isEqualTo(attemptsBefore);
        assertThat(jdbc.queryForObject("select status from logistics.delivery where id=?", String.class, deliveryUuid))
                .isEqualTo(deliveryStatusBefore);
        assertThat(jdbc.queryForObject("select delivered_quantity + rejected_quantity + cancelled_quantity from logistics.fulfillment_line where fulfillment_id=?",
                BigDecimal.class, fulfillmentUuid)).isEqualByComparingTo(unresolvedBefore);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=? and workspace_id=? and aggregate_id=?",
                Integer.class, tenant, workspace, deliveryUuid)).isEqualTo(outboxBefore);
    }

    private MvcResult transition(String fulfillmentId, String etag, String token, String action, String keyPrefix) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fulfillmentId + "/" + action)
                        .header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .header("Idempotency-Key", keyPrefix + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
    }

    private SalesOrderResource createConfirmedDirectOrder() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", "rollback-financial-order-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Rollback delivery\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return new SalesOrderResource(json(created).get("id").asText(), created.getResponse().getHeader("ETag"), sales);
    }
}
