package com.nexa.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP workflow fixture shared by TASK-008 and TASK-010 integration gates. */
public abstract class NexaWorkflowIntegrationSupport extends PostgresIntegrationSupport {
    protected SyntheticWorkspaceIdentity createSyntheticWorkspaceIdentity(String sourceEmail, String roleCode,
            String displayPrefix) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID sourceMembership = UUID.fromString(membershipId(sourceEmail));
        UUID sourceUser = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, sourceMembership);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String localPrefix = displayPrefix.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String email = localPrefix + "-" + suffix + "@icisa-test.local";
        String username = localPrefix.replace('-', '_') + "_" + suffix;

        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,"
                        + "display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                user, email, email, username, username, displayPrefix + " " + suffix);
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) "
                        + "select ?,password_hash,algorithm,changed_at from iam.password_credential where user_id=?",
                user, sourceUser);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?,'INTERNAL','ACTIVE',current_timestamp,current_timestamp,0)",
                membership, workspace, user);
        int assigned = jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id,assigned_at) "
                        + "select ?,?,?,r.id,?,current_timestamp from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.workspace_id is null and r.code=?",
                membership, tenant, workspace, UUID.fromString(membershipId(OWNER_EMAIL)), roleCode);
        if (assigned != 1) throw new IllegalStateException("Synthetic identity must receive exactly one existing role");
        return new SyntheticWorkspaceIdentity(user, membership, email);
    }

    protected void retireSyntheticWorkspaceIdentity(SyntheticWorkspaceIdentity identity) {
        jdbc.update("delete from iam.access_context_selection_ticket where user_id=?", identity.userId());
        jdbc.update("update iam.refresh_session set replaced_by_session_id=null where user_id=?", identity.userId());
        jdbc.update("delete from iam.refresh_session where user_id=?", identity.userId());
        jdbc.update("update tenant_management.workspace_membership set status='DISABLED',updated_at=current_timestamp,"
                        + "version=version+1 where id=? and status='ACTIVE'", identity.membershipId());
    }

    protected record SyntheticWorkspaceIdentity(UUID userId, UUID membershipId, String email) { }

    protected PurchaseRequestResource createApprovedPurchaseRequest() throws Exception {
        return createApprovedPurchaseRequest("CAT-0002");
    }

    protected PurchaseRequestResource createApprovedPurchaseRequest(String catalogItemId) throws Exception {
        if ("CAT-0002".equals(catalogItemId)) { ensureCommercialInventory(); }
        else { ensureCommercialInventory(LocalDate.of(2099, 1, 1), catalogItemId); }
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"catalogItemId\":\"" + catalogItemId + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", etag)
                        .header("Idempotency-Key", "submit-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult inReview = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/reviews")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        MvcResult approved = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/approvals")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", inReview.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        return new PurchaseRequestResource(requestId, approved.getResponse().getHeader("ETag"), sales);
    }

    /** Seeds real sellable inventory for v0.14 commercial backing scenarios. */
    protected void ensureCommercialInventory() throws Exception {
        ensureCommercialInventory(LocalDate.of(2099, 1, 1), "CAT-0002");
    }

    /** Seeds sellable inventory with the requested expiry and catalog item for deterministic FEFO scenarios. */
    protected void ensureCommercialInventory(LocalDate expirationDate, String catalogItemId) throws Exception {
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-COM-" + suffix + "\",\"name\":\"Commercial core warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        String warehouseId = json(createdWarehouse).get("id").asText();
        grantWarehouseAccess(warehouseId);
        warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult createdZone = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-COM-" + suffix + "\",\"name\":\"Commercial core zone\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn();
        String zoneId = json(createdZone).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("Idempotency-Key", "commercial-inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"" + catalogItemId + "\",\"batchNumber\":\"B-COM-" + suffix
                                + "\",\"expirationDate\":\"" + expirationDate + "\",\"quantity\":100,\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
    }

    /** Configures the real Tenant credit account and preserves its current exposure buckets. */
    protected void ensureBuyerCreditAccountConfigured() throws Exception {
        UUID accountId = UUID.fromString(buyerClientAccountId());
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String path = "/api/v1/client-accounts/" + accountId + "/credit-account";
        MvcResult current = mockMvc.perform(get(path).param("currency", "PEN")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        var snapshot = json(current);
        if ("NOT_CONFIGURED".equals(snapshot.get("status").asText())) {
            mockMvc.perform(put(path)
                            .header("Authorization", "Bearer " + owner)
                            .header("If-None-Match", "*")
                            .header("Idempotency-Key", "workflow-credit-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currency\":\"PEN\",\"creditLimit\":42000,\"active\":true}"))
                    .andExpect(status().isCreated());
            return;
        }

        assertThat(snapshot.get("status").asText()).isNotEqualTo("CLOSED");
        if ("ACTIVE".equals(snapshot.get("status").asText())
                && snapshot.get("availableCredit").decimalValue().compareTo(new BigDecimal("20.50")) >= 0) {
            return;
        }
        BigDecimal requestedLimit = snapshot.get("used").decimalValue().add(new BigDecimal("100.00"))
                .max(snapshot.get("creditLimit").decimalValue());
        mockMvc.perform(put(path)
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", current.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "workflow-credit-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"PEN\",\"creditLimit\":" + requestedLimit.toPlainString()
                                + ",\"active\":true}"))
                .andExpect(status().isOk());
    }

    protected SalesOrderResource convert(PurchaseRequestResource request, String key) throws Exception {
        MvcResult converted = mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + request.salesToken())
                        .header("If-Match", request.etag()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        return new SalesOrderResource(json(converted).get("id").asText(), converted.getResponse().getHeader("ETag"), request.salesToken());
    }

    protected SalesOrderResource createSalesOrder() throws Exception {
        return convert(createApprovedPurchaseRequest(), "convert-" + UUID.randomUUID());
    }

    protected SalesOrderResource createConfirmedSalesOrder() throws Exception {
        SalesOrderResource pending = createSalesOrder();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/sales-orders/" + pending.id() + "/confirmations")
                        .header("Authorization", "Bearer " + pending.salesToken())
                        .header("If-Match", pending.etag()))
                .andExpect(status().isOk()).andReturn();
        return new SalesOrderResource(pending.id(), confirmed.getResponse().getHeader("ETag"), pending.salesToken());
    }

    protected DispatchResource createReservedDispatch() throws Exception {
        return createReservedDispatch("CAT-0002", LocalDate.of(2098, 1, 1));
    }

    protected DispatchResource createReservedDispatch(String catalogItemId, LocalDate expirationDate) throws Exception {
        SalesOrderResource pending = convert(createApprovedPurchaseRequest(catalogItemId), "convert-" + UUID.randomUUID());
        MvcResult confirmed = mockMvc.perform(post("/api/v1/sales-orders/" + pending.id() + "/confirmations")
                        .header("Authorization", "Bearer " + pending.salesToken()).header("If-Match", pending.etag()))
                .andExpect(status().isOk()).andReturn();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WH-" + suffix + "\",\"name\":\"Dispatch test warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        String warehouseId = json(createdWarehouse).get("id").asText();
        grantWarehouseAccess(warehouseId);
        warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult createdZone = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones").header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Z-" + suffix + "\",\"name\":\"Frozen dispatch zone\",\"type\":\"FROZEN\",\"temperatureMin\":-25,\"temperatureMax\":-15}"))
                .andExpect(status().isCreated()).andReturn();
        String zoneId = json(createdZone).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + warehouse).header("Idempotency-Key", "inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId + "\",\"catalogItemId\":\"" + catalogItemId + "\",\"batchNumber\":\"B-" + suffix + "\",\"expirationDate\":\"" + expirationDate + "\",\"quantity\":20,\"unit\":\"UNIT\",\"temperatureReading\":-18}"))
                .andExpect(status().isCreated());
        MvcResult reservation = mockMvc.perform(post("/api/v1/fulfillment-candidates/" + pending.id() + "/inventory-reservations")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", confirmed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "reserve-" + suffix))
                .andExpect(status().isCreated()).andReturn();
        String reservationId = json(reservation).get("id").asText();
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        MvcResult dispatch = mockMvc.perform(post("/api/v1/inventory-reservations/" + reservationId + "/dispatch-orders")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", reservation.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "dispatch-create-" + suffix))
                .andExpect(status().isCreated()).andReturn();
        return new DispatchResource(json(dispatch).get("id").asText(), json(dispatch).get("dispatchNumber").asText(), dispatch.getResponse().getHeader("ETag"), logistics, reservationId, confirmed.getResponse().getHeader("ETag"), pending.id());
    }

    private void grantWarehouseAccess(String warehouseId) throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
    }

    protected tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    public record PurchaseRequestResource(String id, String etag, String salesToken) { }
    public record SalesOrderResource(String id, String etag, String salesToken) { }
    public record DispatchResource(String id, String dispatchNumber, String etag, String logisticsToken, String reservationId, String reservationEtag, String salesOrderId) { }
}
