package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestSalesManagementIT extends NexaWorkflowIntegrationSupport {
    private UUID priceListId;
    private UUID priceListItemId;
    private UUID customerTermsId;

    @AfterEach
    void removePriceListFixture() {
        if (customerTermsId != null) jdbc.update("delete from catalog_management.customer_terms where terms_id=?", customerTermsId);
        if (priceListItemId != null) jdbc.update("delete from catalog_management.price_list_item where item_id=?", priceListItemId);
        if (priceListId != null) jdbc.update("delete from catalog_management.price_list where price_list_id=?", priceListId);
    }

    @Test
    void purchaseRequestUsesPermittedPriceListAndRejectsStalePriceAtSubmission() throws Exception {
        ensureCommercialInventory();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.fromString(buyerClientAccountId());
        UUID sku = jdbc.queryForObject("select id from catalog_management.sellable_sku "
                + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002'",
                UUID.class, tenant, workspace);
        priceListId = UUID.randomUUID();
        priceListItemId = UUID.randomUUID();
        customerTermsId = UUID.randomUUID();
        Timestamp startsAt = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
        Timestamp endsAt = Timestamp.from(Instant.parse("2099-12-31T00:00:00Z"));
        Timestamp createdAt = Timestamp.from(Instant.now());
        jdbc.update("insert into catalog_management.price_list (price_list_id,tenant_id,workspace_id,code,name,currency,status,valid_from,valid_to,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,'PEN','ACTIVE',?,?,?, ?,0)", priceListId, tenant, workspace,
                "IT-" + priceListId, "Buyer price list", startsAt, endsAt, createdAt, createdAt);
        jdbc.update("insert into catalog_management.price_list_item (item_id,tenant_id,workspace_id,price_list_id,sku_id,unit_price,currency,valid_from,valid_to) "
                        + "values (?,?,?,?,?,?,'PEN',?,?)", priceListItemId, tenant, workspace,
                priceListId, sku, new java.math.BigDecimal("275.00"), startsAt, endsAt);
        jdbc.update("insert into catalog_management.customer_terms (terms_id,tenant_id,workspace_id,customer_account_id,price_list_id,credit_days,currency,valid_from,valid_to,created_at,version) "
                        + "values (?,?,?,?,?,0,'PEN',?,?,?,0)", customerTermsId, tenant, workspace, account,
                priceListId, startsAt, endsAt, createdAt);

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Price list test\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}] }"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        UUID request = UUID.fromString(requestId);
        assertThat(jdbc.queryForObject("select unit_price_amount from sales.purchase_request_line "
                + "where purchase_request_id=? and superseded_at is null", java.math.BigDecimal.class, request))
                .isEqualByComparingTo("275");

        jdbc.update("update catalog_management.price_list_item set unit_price=? where item_id=?",
                new java.math.BigDecimal("300.00"), priceListItemId);
        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "stale-price-" + uuid()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMERCIAL_POLICY_CHANGED"));
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, request))
                .isEqualTo("DRAFT");
    }

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
    void buyerRejectionPreservesOriginalTermsAndHistoryAndReplaysIdempotently() throws Exception {
        ProposedChange scenario = createProposedMaterialChange();
        String key = "material-reject-" + uuid();
        mockMvc.perform(post("/api/v1/purchase-requests/" + scenario.requestId() + "/material-changes/"
                        + scenario.proposalId() + "/rejections")
                        .header("Authorization", "Bearer " + scenario.buyerToken())
                        .header("If-Match", scenario.etag())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.lines[0].quantity").value(1));

        mockMvc.perform(post("/api/v1/purchase-requests/" + scenario.requestId() + "/material-changes/"
                        + scenario.proposalId() + "/rejections")
                        .header("Authorization", "Bearer " + scenario.buyerToken())
                        .header("If-Match", scenario.etag())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.lines[0].quantity").value(1));

        mockMvc.perform(get("/api/v1/purchase-requests/" + scenario.requestId() + "/material-changes")
                        .header("Authorization", "Bearer " + scenario.buyerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(scenario.proposalId()))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].originalTerms.lines[0].quantity").value(1))
                .andExpect(jsonPath("$[0].proposedTerms.lines[0].quantity").value(2));

        assertThat(jdbc.queryForObject("select sum(quantity) from sales.purchase_request_line "
                        + "where purchase_request_id=? and superseded_at is null", java.math.BigDecimal.class, scenario.request()))
                .isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("select status from sales.purchase_request_material_change where id=?",
                String.class, UUID.fromString(scenario.proposalId()))).isEqualTo("REJECTED");
    }

    @Test
    void terminalPurchaseRequestTransitionExpiresOpenMaterialChangeAndKeepsHistory() throws Exception {
        ProposedChange scenario = createProposedMaterialChange();
        mockMvc.perform(post("/api/v1/purchase-requests/" + scenario.requestId() + "/withdrawals")
                        .header("Authorization", "Bearer " + scenario.buyerToken())
                        .header("If-Match", scenario.etag())
                        .header("Idempotency-Key", "material-withdraw-" + uuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        mockMvc.perform(get("/api/v1/purchase-requests/" + scenario.requestId() + "/material-changes")
                        .header("Authorization", "Bearer " + scenario.buyerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[0].originalTerms.lines[0].quantity").value(1))
                .andExpect(jsonPath("$[0].proposedTerms.lines[0].quantity").value(2));
        assertThat(jdbc.queryForObject("select resolved_at is not null from sales.purchase_request_material_change where id=?",
                Boolean.class, UUID.fromString(scenario.proposalId()))).isTrue();
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

    private ProposedChange createProposedMaterialChange() throws Exception {
        ensureCommercialInventory();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentOption\":\"CREDIT_LINE\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Material change decision test\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}] }"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-decision-submit-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult proposed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/material-changes")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "material-decision-propose-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Buyer-requested quantity change\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":2,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andReturn();
        return new ProposedChange(requestId, json(proposed).get("id").asText(), buyer,
                proposed.getResponse().getHeader("ETag"), UUID.fromString(requestId));
    }

    private record ProposedChange(String requestId, String proposalId, String buyerToken, String etag, UUID request) { }
}
