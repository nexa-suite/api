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
    @Test void companyOwnerCannotReviewApproveProposeRejectOrConvertAndSalesCanStillConvert() throws Exception {
        var request = createApprovedPurchaseRequest();
        UUID requestId = UUID.fromString(request.id());
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");

        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/material-changes")
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", request.etag())
                        .header("Idempotency-Key", "owner-propose-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Quantity adjustment\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":2,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/reviews")
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", request.etag()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/approvals")
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", request.etag()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/rejections")
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", request.etag())
                        .header("Idempotency-Key", "owner-reject-" + uuid()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", request.etag())
                        .header("Idempotency-Key", "owner-convert-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?", String.class, requestId))
                .isEqualTo("SUBMITTED");
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, requestId))
                .isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event e join sales.sales_order o on e.aggregate_id=o.id "
                + "where o.source_purchase_request_id=? and e.event_type='SALES_ORDER_CONFIRMED'", Integer.class, requestId))
                .isZero();

        var order = convert(request, "sales-after-owner-denial-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test void convertsSubmittedPurchaseRequestThroughRealHttpAndPersistsBothAggregates() throws Exception {
        var request = createApprovedPurchaseRequest();
        var order = convert(request, "conversion-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo("CONVERTED");
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, java.util.UUID.fromString(request.id()))).isEqualTo(1);
        String orderId = tenantJdbc().queryForObject("select id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()));
        assertThat(tenantJdbc().queryForObject("select status from sales.sales_order where id=?", String.class, java.util.UUID.fromString(orderId))).isEqualTo("CONFIRMED");
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event where aggregate_id=? and event_type='SALES_ORDER_CONFIRMED'", Integer.class, java.util.UUID.fromString(orderId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select status from sales.commercial_commitment where purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo("CONVERTED");
        assertThat(tenantJdbc().queryForObject("select sales_order_id::text from sales.commercial_commitment where purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(orderId);
        assertThat(tenantJdbc().queryForObject("select created_by_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(membershipId(SALES_EMAIL));
        assertThat(tenantJdbc().queryForObject("select buyer_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(tenantJdbc().queryForObject("select buyer_membership_id::text from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id())));
    }

    @Test void conversionRejectsUnacceptedMaterialChangeThenSucceedsAfterBuyerAcceptance() throws Exception {
        ensureBuyerCreditAccountConfigured();
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

        UUID commitmentId = tenantJdbc().queryForObject("select id from sales.commercial_commitment where purchase_request_id=?", UUID.class, request);
        long commitmentVersion = tenantJdbc().queryForObject("select version from sales.commercial_commitment where id=?", Long.class, commitmentId);
        String commitmentStatus = tenantJdbc().queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId);
        BigDecimal committedQuantity = tenantJdbc().queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?", BigDecimal.class, commitmentId);
        UUID backingId = tenantJdbc().queryForObject("select id from warehouse.inventory_backing where commercial_commitment_id=?", UUID.class, commitmentId);
        long backingVersion = tenantJdbc().queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId);
        BigDecimal backedRequestQuantity = tenantJdbc().queryForObject("select sum(requested_quantity) from warehouse.inventory_backing_line where backing_id=?", BigDecimal.class, backingId);

        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/order-conversions")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", proposed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "unaccepted-conversion-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, request)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from integration.outbox_event e join sales.sales_order o on e.aggregate_id=o.id where o.source_purchase_request_id=? and e.event_type='SALES_ORDER_CONFIRMED'", Integer.class, request)).isZero();
        assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?", String.class, request)).isEqualTo("CHANGES_PROPOSED");
        assertThat(tenantJdbc().queryForObject("select version from sales.commercial_commitment where id=?", Long.class, commitmentId)).isEqualTo(commitmentVersion);
        assertThat(tenantJdbc().queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId)).isEqualTo(commitmentStatus);
        assertThat(tenantJdbc().queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?", BigDecimal.class, commitmentId)).isEqualByComparingTo(committedQuantity);
        assertThat(tenantJdbc().queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId)).isEqualTo(backingVersion);
        assertThat(tenantJdbc().queryForObject("select sum(requested_quantity) from warehouse.inventory_backing_line where backing_id=?", BigDecimal.class, backingId)).isEqualByComparingTo(backedRequestQuantity);

        MvcResult accepted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes/" + proposalId + "/acceptances")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", proposed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-accept-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();

        UUID expectedSkuId = tenantJdbc().queryForObject("select id from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002' and status='ACTIVE'",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        UUID expectedFamilyId = tenantJdbc().queryForObject("select family_id from catalog_management.sellable_sku where id=?",
                UUID.class, expectedSkuId);
        String expectedSkuCode = tenantJdbc().queryForObject("select sku_code from catalog_management.sellable_sku where id=?",
                String.class, expectedSkuId);
        String expectedFamilyCode = tenantJdbc().queryForObject("select family_code from catalog_management.product_family where id=?",
                String.class, expectedFamilyId);
        assertThat(tenantJdbc().queryForObject("select sku_id from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", UUID.class, request))
                .isEqualTo(expectedSkuId);
        assertThat(tenantJdbc().queryForObject("select product_family_id from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", UUID.class, request))
                .isEqualTo(expectedFamilyId);
        assertThat(tenantJdbc().queryForObject("select sku_code_snapshot from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", String.class, request))
                .isEqualTo(expectedSkuCode);
        assertThat(tenantJdbc().queryForObject("select product_family_code_snapshot from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", String.class, request))
                .isEqualTo(expectedFamilyCode);

        var order = convert(new PurchaseRequestResource(requestId, accepted.getResponse().getHeader("ETag"), sales), "accepted-material-conversion-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?", String.class, request)).isEqualTo("CONVERTED");
        assertThat(tenantJdbc().queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitmentId)).isEqualTo("CONVERTED");
        assertThat(tenantJdbc().queryForObject("select sum(quantity) from sales.sales_order_line where sales_order_id=?", BigDecimal.class, UUID.fromString(order.id()))).isEqualByComparingTo("2");
        assertThat(tenantJdbc().queryForObject("select sku_id from sales.sales_order_line where sales_order_id=?",
                UUID.class, UUID.fromString(order.id()))).isEqualTo(expectedSkuId);
        assertThat(tenantJdbc().queryForObject("select product_family_id from sales.sales_order_line where sales_order_id=?",
                UUID.class, UUID.fromString(order.id()))).isEqualTo(expectedFamilyId);
        assertThat(tenantJdbc().queryForObject("select sku_code_snapshot from sales.sales_order_line where sales_order_id=?",
                String.class, UUID.fromString(order.id()))).isEqualTo(expectedSkuCode);
        assertThat(tenantJdbc().queryForObject("select product_family_code_snapshot from sales.sales_order_line where sales_order_id=?",
                String.class, UUID.fromString(order.id()))).isEqualTo(expectedFamilyCode);

        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult fulfillment = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.etag())
                        .header("Idempotency-Key", "material-change-fulfillment-" + uuid()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].skuId").value(expectedSkuId.toString()))
                .andReturn();
        assertThat(json(fulfillment).path("lines").get(0).path("skuId").asText()).isEqualTo(expectedSkuId.toString());
    }

    @Test void materialChangeAcceptanceRejectsUnavailableSkuWithoutChangingRequestOrCommitment() throws Exception {
        ensureBuyerCreditAccountConfigured();
        ensureCommercialInventory();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID skuId = tenantJdbc().queryForObject("select id from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002' and status='ACTIVE'",
                UUID.class, tenant, workspace);
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Unavailable SKU material change test\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        UUID request = UUID.fromString(requestId);
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "unavailable-sku-submit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult proposed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "unavailable-sku-propose-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Quantity adjustment for buyer review\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":2,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andReturn();
        String proposalId = json(proposed).get("id").asText();

        UUID originalLineId = tenantJdbc().queryForObject("select id from sales.purchase_request_line "
                + "where purchase_request_id=? and superseded_at is null", UUID.class, request);
        BigDecimal originalQuantity = tenantJdbc().queryForObject("select quantity from sales.purchase_request_line "
                + "where id=?", BigDecimal.class, originalLineId);
        UUID commitmentId = tenantJdbc().queryForObject("select id from sales.commercial_commitment where purchase_request_id=?",
                UUID.class, request);
        long commitmentVersion = tenantJdbc().queryForObject("select version from sales.commercial_commitment where id=?",
                Long.class, commitmentId);
        BigDecimal committedQuantity = tenantJdbc().queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?",
                BigDecimal.class, commitmentId);
        UUID backingId = tenantJdbc().queryForObject("select id from warehouse.inventory_backing where commercial_commitment_id=?",
                UUID.class, commitmentId);
        long backingVersion = tenantJdbc().queryForObject("select version from warehouse.inventory_backing where id=?",
                Long.class, backingId);

        try {
            tenantJdbc().update("update catalog_management.sellable_sku set status='INACTIVE',updated_at=current_timestamp,version=version+1 "
                    + "where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'", tenant, workspace, skuId);
            mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes/" + proposalId + "/acceptances")
                            .header("Authorization", "Bearer " + buyer)
                            .header("If-Match", proposed.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", "unavailable-sku-accept-" + uuid()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CATALOG_ITEM_NOT_FOUND"));
        } finally {
            tenantJdbc().update("update catalog_management.sellable_sku set status='ACTIVE',updated_at=current_timestamp,version=version+1 "
                    + "where tenant_id=? and workspace_id=? and id=? and status='INACTIVE'", tenant, workspace, skuId);
        }

        assertThat(tenantJdbc().queryForObject("select id from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", UUID.class, request))
                .isEqualTo(originalLineId);
        assertThat(tenantJdbc().queryForObject("select quantity from sales.purchase_request_line where id=?",
                BigDecimal.class, originalLineId)).isEqualByComparingTo(originalQuantity);
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.purchase_request_line "
                + "where purchase_request_id=? and superseded_at is null", Integer.class, request)).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?", String.class, request))
                .isEqualTo("CHANGES_PROPOSED");
        assertThat(tenantJdbc().queryForObject("select version from sales.commercial_commitment where id=?",
                Long.class, commitmentId)).isEqualTo(commitmentVersion);
        assertThat(tenantJdbc().queryForObject("select sum(quantity) from sales.commercial_commitment_line where commitment_id=?",
                BigDecimal.class, commitmentId)).isEqualByComparingTo(committedQuantity);
        assertThat(tenantJdbc().queryForObject("select version from warehouse.inventory_backing where id=?",
                Long.class, backingId)).isEqualTo(backingVersion);
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?",
                Integer.class, request)).isZero();
    }
}
