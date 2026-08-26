package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.14 commercial, inventory backing, credit and idempotency gates. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CommercialInventoryCoreIT extends NexaWorkflowIntegrationSupport {

    @Test
    void directOrderConfirmsWithoutSyntheticPurchaseRequestAndReplaysExactly() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String key = "direct-confirm-" + uuid();
        String body = directBody("IMMEDIATE", "2");

        MvcResult created = directOrder(sales, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.originType").value("DIRECT_ORDER"))
                .andReturn();
        String orderId = json(created).get("id").asText();

        MvcResult replay = directOrder(sales, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();

        directOrder(sales, key, directBody("IMMEDIATE", "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        UUID order = UUID.fromString(orderId);
        String commitmentId = jdbc.queryForObject(
                "select commercial_commitment_id::text from sales.sales_order where tenant_id=?::uuid and workspace_id=?::uuid and id=?",
                String.class, tenantId(), workspaceId(), order);
        assertThat(jdbc.queryForObject("select source_purchase_request_id from sales.sales_order where id=?", Object.class, order)).isNull();
        assertThat(jdbc.queryForObject("select order_source from sales.sales_order where id=?", String.class, order)).isEqualTo("DIRECT_ORDER");
        assertThat(jdbc.queryForObject("select origin_type from sales.sales_order where id=?", String.class, order)).isEqualTo("DIRECT_ORDER");
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, UUID.fromString(commitmentId))).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_backing where commercial_commitment_id=? and status='BACKED'", Integer.class, UUID.fromString(commitmentId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where event_type='SALES_ORDER_CONFIRMED' and aggregate_id=?", Integer.class, order)).isEqualTo(1);
        assertThat(json(replay).get("id").asText()).isEqualTo(orderId);
    }

    @Test
    void creditLineConfirmationPostsReceivableAndConsumesReservation() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult created = directOrder(sales, "direct-credit-success-" + uuid(), directBody("CREDIT_LINE", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();
        UUID order = UUID.fromString(json(created).get("id").asText());

        UUID receivable = jdbc.queryForObject("select id from payments.receivable where tenant_id=?::uuid and workspace_id=?::uuid and subject_type='SALES_ORDER' and subject_id=?", UUID.class, tenantId(), workspaceId(), order);
        assertThat(receivable).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments.credit_reservation where tenant_id=?::uuid and workspace_id=?::uuid and sales_order_id=?", String.class, tenantId(), workspaceId(), order)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=?::uuid and workspace_id=?::uuid and event_type='RECEIVABLE_CREATED' and aggregate_id=?", Integer.class, tenantId(), workspaceId(), receivable)).isEqualTo(1);
    }

    @Test
    void purchaseRequestCreditLineTransfersReservationAndPostsReceivableOnConfirmation() throws Exception {
        ensureCommercialInventory();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Credit PR\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}] }"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "credit-pr-submit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult reviewed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/reviews")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        MvcResult approved = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/approvals")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", reviewed.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        MvcResult converted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/order-conversions")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", approved.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "credit-pr-convert-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(converted).get("id").asText();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/confirmations")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", converted.getResponse().getHeader("ETag")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();

        UUID order = UUID.fromString(orderId);
        UUID receivable = jdbc.queryForObject("select id from payments.receivable where tenant_id=?::uuid and workspace_id=?::uuid and subject_type='SALES_ORDER' and subject_id=?", UUID.class, tenantId(), workspaceId(), order);
        assertThat(receivable).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments.credit_reservation where tenant_id=?::uuid and workspace_id=?::uuid and purchase_request_id=?", String.class, tenantId(), workspaceId(), UUID.fromString(requestId))).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select status from payments.credit_reservation where tenant_id=?::uuid and workspace_id=?::uuid and sales_order_id=?", String.class, tenantId(), workspaceId(), order)).isEqualTo("CONSUMED");
        assertThat(json(confirmed).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void conversionAtOrAfterExpiryReleasesBackingAndMarksCommitmentExpired() throws Exception {
        PurchaseRequestResource request = createApprovedPurchaseRequest();
        UUID requestId = UUID.fromString(request.id());
        jdbc.update("update sales.purchase_request set expires_at=current_timestamp where tenant_id=?::uuid and workspace_id=?::uuid and id=?",
                tenantId(), workspaceId(), requestId);

        mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + request.salesToken())
                        .header("If-Match", request.etag())
                        .header("Idempotency-Key", "expired-convert-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_EXPIRED"));

        UUID commitment = jdbc.queryForObject("select id from sales.commercial_commitment where tenant_id=?::uuid and workspace_id=?::uuid and purchase_request_id=?", UUID.class, tenantId(), workspaceId(), requestId);
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, requestId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitment)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_backing where commercial_commitment_id=?", String.class, commitment)).isEqualTo("RELEASED");
    }

    @Test
    void prepaidDirectOrderStaysPendingUntilPaymentConfirmation() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult pending = directOrder(sales, "direct-prepaid-" + uuid(), directBody("PREPAID", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        UUID order = UUID.fromString(json(pending).get("id").asText());
        UUID commitment = jdbc.queryForObject("select commercial_commitment_id from sales.sales_order where id=?", UUID.class, order);

        mockMvc.perform(post("/api/v1/sales-orders/" + order + "/confirmations")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", pending.getResponse().getHeader("ETag")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_REQUIRED"));
        assertThat(jdbc.queryForObject("select status from sales.sales_order where id=?", String.class, order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitment)).isEqualTo("ACTIVE");

        mockMvc.perform(post("/api/v1/sales-orders/" + order + "/cancellations")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", pending.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "direct-prepaid-cancel-" + uuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where id=?", String.class, commitment)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_backing where commercial_commitment_id=?", String.class, commitment)).isEqualTo("RELEASED");
    }

    @Test
    void insufficientSellableAvailabilityRollsBackCommitmentOrderBackingAndOutbox() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String tenant = tenantId();
        String workspace = workspaceId();
        int commitmentsBefore = jdbc.queryForObject("select count(*) from sales.commercial_commitment where tenant_id=?::uuid and workspace_id=?::uuid and origin_type='DIRECT_ORDER'", Integer.class, tenant, workspace);
        int ordersBefore = jdbc.queryForObject("select count(*) from sales.sales_order where tenant_id=?::uuid and workspace_id=?::uuid and order_source='DIRECT_ORDER'", Integer.class, tenant, workspace);
        int backingBefore = jdbc.queryForObject("select count(*) from warehouse.inventory_backing where tenant_id=?::uuid and workspace_id=?::uuid", Integer.class, tenant, workspace);
        int outboxBefore = jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=?::uuid and workspace_id=?::uuid and event_type='SALES_ORDER_CONFIRMED'", Integer.class, tenant, workspace);

        directOrder(sales, "direct-short-" + uuid(), directBody("IMMEDIATE", "1000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SELLABLE_AVAILABILITY"));

        assertThat(jdbc.queryForObject("select count(*) from sales.commercial_commitment where tenant_id=?::uuid and workspace_id=?::uuid and origin_type='DIRECT_ORDER'", Integer.class, tenant, workspace)).isEqualTo(commitmentsBefore);
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where tenant_id=?::uuid and workspace_id=?::uuid and order_source='DIRECT_ORDER'", Integer.class, tenant, workspace)).isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_backing where tenant_id=?::uuid and workspace_id=?::uuid", Integer.class, tenant, workspace)).isEqualTo(backingBefore);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=?::uuid and workspace_id=?::uuid and event_type='SALES_ORDER_CONFIRMED'", Integer.class, tenant, workspace)).isEqualTo(outboxBefore);
    }

    @Test
    void purchaseRequestShortageRollsBackSubmittedStateCommitmentBackingAndCredit() throws Exception {
        ensureCommercialInventory();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Shortage rollback\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1000000,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        UUID request = UUID.fromString(requestId);
        String etag = created.getResponse().getHeader("ETag");

        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "submit-shortage-" + uuid()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SELLABLE_AVAILABILITY"));

        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, request)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from sales.commercial_commitment where purchase_request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_backing b join sales.commercial_commitment c on c.id=b.commercial_commitment_id where c.purchase_request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments.credit_reservation where purchase_request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sales.idempotency_record where resource_id=? and operation='purchase-request-submission'", Integer.class, request)).isZero();
    }

    @Test
    void insufficientCreditRollsBackInventoryBackingAndCommercialRows() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID client = UUID.fromString(buyerClientAccountId());
        CreditAccountState original = jdbc.query(
                "select credit_limit,credit_exposure,reserved_exposure,status from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                (rs, row) -> new CreditAccountState(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4)), tenant, workspace, client)
                .stream().findFirst().orElse(null);
        BigDecimal originalClientLimit = jdbc.queryForObject("select credit_limit from sales.client_account where tenant_id=? and workspace_id=? and id=?", BigDecimal.class, tenant, workspace, client);
        int commitmentsBefore = jdbc.queryForObject("select count(*) from sales.commercial_commitment where tenant_id=? and workspace_id=? and origin_type='DIRECT_ORDER'", Integer.class, tenant, workspace);
        int ordersBefore = jdbc.queryForObject("select count(*) from sales.sales_order where tenant_id=? and workspace_id=? and order_source='DIRECT_ORDER'", Integer.class, tenant, workspace);
        int backingBefore = jdbc.queryForObject("select count(*) from warehouse.inventory_backing where tenant_id=? and workspace_id=?", Integer.class, tenant, workspace);
        int reservationsBefore = jdbc.queryForObject("select count(*) from payments.credit_reservation where tenant_id=? and workspace_id=? and commercial_commitment_id is not null and status='RESERVED'", Integer.class, tenant, workspace);
        try {
            jdbc.update("update sales.client_account set credit_limit=0,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=?", tenant, workspace, client);
            jdbc.update("update payments.credit_account set credit_limit=0,credit_exposure=0,reserved_exposure=0,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                    tenant, workspace, client);

            directOrder(sales, "direct-credit-short-" + uuid(), directBody("CREDIT_LINE", "1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDIT"));

            assertThat(jdbc.queryForObject("select count(*) from sales.commercial_commitment where tenant_id=? and workspace_id=? and origin_type='DIRECT_ORDER'", Integer.class, tenant, workspace)).isEqualTo(commitmentsBefore);
            assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where tenant_id=? and workspace_id=? and order_source='DIRECT_ORDER'", Integer.class, tenant, workspace)).isEqualTo(ordersBefore);
            assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_backing where tenant_id=? and workspace_id=?", Integer.class, tenant, workspace)).isEqualTo(backingBefore);
            assertThat(jdbc.queryForObject("select count(*) from payments.credit_reservation where tenant_id=? and workspace_id=? and commercial_commitment_id is not null and status='RESERVED'", Integer.class, tenant, workspace)).isEqualTo(reservationsBefore);
        } finally {
            jdbc.update("update sales.client_account set credit_limit=?,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=?", originalClientLimit, tenant, workspace, client);
            if (original != null) {
                jdbc.update("update payments.credit_account set credit_limit=?,credit_exposure=?,reserved_exposure=?,status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                        original.limit(), original.exposure(), original.reserved(), original.status(), tenant, workspace, client);
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions directOrder(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/direct-orders")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String directBody(String paymentOption, String quantity) {
        return "{\"clientAccountId\":\"" + buyerClientAccountId() + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Direct order delivery\",\"paymentOption\":\"" + paymentOption + "\",\"comment\":\"v0.14 direct order\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":" + quantity + ",\"unit\":\"UNIT\"}]}";
    }

    private record CreditAccountState(BigDecimal limit, BigDecimal exposure, BigDecimal reserved, String status) { }
}
