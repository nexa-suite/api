package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport.PurchaseRequestResource;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOrderConversionIT extends NexaWorkflowIntegrationSupport {
    @Test void convertsSubmittedPurchaseRequestThroughRealHttpAndPersistsBothAggregates() throws Exception {
        var request = createApprovedPurchaseRequest();
        var order = convert(request, "conversion-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, java.util.UUID.fromString(request.id()))).isEqualTo(1);
        String orderId = jdbc.queryForObject("select id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()));
        assertThat(jdbc.queryForObject("select status from sales.sales_order where id=?", String.class, java.util.UUID.fromString(orderId))).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where aggregate_id=? and event_type='SALES_ORDER_CONFIRMED'", Integer.class, java.util.UUID.fromString(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select sales_order_id::text from sales.commercial_commitment where purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(orderId);
        assertThat(jdbc.queryForObject("select created_by_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(membershipId(SALES_EMAIL));
        assertThat(jdbc.queryForObject("select buyer_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(jdbc.queryForObject("select buyer_membership_id::text from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id())));
    }

    @Test void conversionRejectsUnacceptedMaterialChangeThenSucceedsAfterBuyerAcceptance() throws Exception {
        ensureCommercialInventory();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Material change conversion test\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        UUID request = UUID.fromString(requestId);
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-submit-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();

        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult proposed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-propose-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Quantity adjustment for buyer review\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":2,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED")).andReturn();
        String proposalId = json(proposed).get("id").asText();

        UUID commitmentId = jdbc.queryForObject("select id from sales.commercial_commitment where purchase_request_id=?", UUID.class, request);
        long commitmentVersion = jdbc.queryForObject("select version from sales.commercial_commitment where id=?", Long.class, commitmentId);
        String commitmentStatus = jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId);
        BigDecimal committedQuantity = jdbc.queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?", BigDecimal.class, commitmentId);
        UUID backingId = jdbc.queryForObject("select id from warehouse.inventory_backing where commercial_commitment_id=?", UUID.class, commitmentId);
        long backingVersion = jdbc.queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId);
        BigDecimal backedRequestQuantity = jdbc.queryForObject("select sum(requested_quantity) from warehouse.inventory_backing_line where backing_id=?", BigDecimal.class, backingId);

        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/order-conversions")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", proposed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "unaccepted-conversion-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event e join sales.sales_order o on e.aggregate_id=o.id where o.source_purchase_request_id=? and e.event_type='SALES_ORDER_CONFIRMED'", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, request)).isEqualTo("CHANGES_PROPOSED");
        assertThat(jdbc.queryForObject("select version from sales.commercial_commitment where id=?", Long.class, commitmentId)).isEqualTo(commitmentVersion);
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId)).isEqualTo(commitmentStatus);
        assertThat(jdbc.queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?", BigDecimal.class, commitmentId)).isEqualByComparingTo(committedQuantity);
        assertThat(jdbc.queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId)).isEqualTo(backingVersion);
        assertThat(jdbc.queryForObject("select sum(requested_quantity) from warehouse.inventory_backing_line where backing_id=?", BigDecimal.class, backingId)).isEqualByComparingTo(backedRequestQuantity);

        MvcResult accepted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes/" + proposalId + "/acceptances")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", proposed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-accept-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();

        var order = convert(new PurchaseRequestResource(requestId, accepted.getResponse().getHeader("ETag"), sales), "accepted-material-conversion-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, request)).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId)).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select sum(quantity) from sales.sales_order_line where sales_order_id=?", BigDecimal.class, UUID.fromString(order.id()))).isEqualByComparingTo("2");
    }
}
