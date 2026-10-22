package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class MinimumDeliveryLeadTimeHttpIT extends NexaWorkflowIntegrationSupport {
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    @Test
    void buyerAndSalesDatesUseWorkspaceLeadTimeAndConversionKeepsAcceptedDate() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String workspace = workspaceId();
        String settingsPath = "/api/v1/workspaces/" + workspace + "/operational-settings";
        MvcResult originalSettings = mockMvc.perform(get(settingsPath)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(originalSettings.getResponse().getHeader("ETag")).isNotBlank();
        int originalLeadDays = json(originalSettings).path("minimumDeliveryLeadDays").asInt();

        LocalDate today = LocalDate.now(Clock.systemUTC());
        LocalDate rejectedDate = today.plusDays(4);
        LocalDate acceptedDate = today.plusDays(5);
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyerMembership = membershipId(BUYER_EMAIL);
        String clientAccount = buyerClientAccountId();

        try {
            ensureCommercialInventory();
            setLeadDays(owner, settingsPath, 5);
            assertRoleCanManageLeadTime("COMPANY_OWNER", owner, settingsPath, 5);
            assertRoleCanManageLeadTime("TENANT_ADMIN", null, settingsPath, 5);

            int priorRejectedCount = tenantJdbc().queryForObject(
                    "select count(*) from sales.purchase_request where buyer_membership_id=? and requested_delivery_date=?",
                    Integer.class, UUID.fromString(buyerMembership), rejectedDate);
            mockMvc.perform(post("/api/v1/purchase-requests")
                            .header("Authorization", "Bearer " + buyer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(purchaseRequestBody(rejectedDate)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.requestedDeliveryDate").value(rejectedDate.toString()))
                    .andExpect(jsonPath("$.earliestAllowedDeliveryDate").value(acceptedDate.toString()))
                    .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(5));
            assertThat(tenantJdbc().queryForObject(
                    "select count(*) from sales.purchase_request where buyer_membership_id=? and requested_delivery_date=?",
                    Integer.class, UUID.fromString(buyerMembership), rejectedDate)).isEqualTo(priorRejectedCount);

            MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                            .header("Authorization", "Bearer " + buyer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(purchaseRequestBody(acceptedDate)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String requestId = json(created).path("id").asText();
            UUID requestUuid = UUID.fromString(requestId);

            mockMvc.perform(patch("/api/v1/purchase-requests/" + requestId)
                            .header("Authorization", "Bearer " + buyer)
                            .header("If-Match", created.getResponse().getHeader("ETag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestedDeliveryDate\":\"" + rejectedDate + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.earliestAllowedDeliveryDate").value(acceptedDate.toString()))
                    .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(5));
            assertThat(tenantJdbc().queryForObject(
                    "select requested_delivery_date from sales.purchase_request where id=?", LocalDate.class, requestUuid))
                    .isEqualTo(acceptedDate);
            assertThat(tenantJdbc().queryForObject(
                    "select version from sales.purchase_request where id=?", Long.class, requestUuid)).isZero();

            MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                            .header("Authorization", "Bearer " + buyer)
                            .header("If-Match", created.getResponse().getHeader("ETag"))
                            .header("Idempotency-Key", "lead-time-submit-" + uuid()))
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult reviewed = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/reviews")
                            .header("Authorization", "Bearer " + sales)
                            .header("If-Match", submitted.getResponse().getHeader("ETag")))
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult approved = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/approvals")
                            .header("Authorization", "Bearer " + sales)
                            .header("If-Match", reviewed.getResponse().getHeader("ETag")))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(json(approved).path("status").asText()).isEqualTo("SUBMITTED");

            MvcResult draft = mockMvc.perform(post("/api/v1/sales-orders/manual-drafts")
                            .header("Authorization", "Bearer " + sales)
                            .header("Idempotency-Key", "lead-time-manual-draft-" + uuid()))
                    .andExpect(status().isCreated())
                    .andReturn();
            String draftId = json(draft).path("id").asText();
            String clientRequest = "{\"clientAccountId\":\"" + clientAccount + "\","
                    + "\"requestedDeliveryDate\":\"" + rejectedDate + "\",\"priority\":\"HIGH\","
                    + "\"paymentPreference\":\"CASH\",\"currency\":\"PEN\"}";
            mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/client")
                            .header("Authorization", "Bearer " + sales)
                            .header("If-Match", draft.getResponse().getHeader("ETag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(clientRequest))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.earliestAllowedDeliveryDate").value(acceptedDate.toString()))
                    .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(5));
            assertThat(tenantJdbc().queryForObject(
                    "select version from sales.manual_sales_order_draft where id=?", Long.class, UUID.fromString(draftId)))
                    .isZero();

            String acceptedClientRequest = clientRequest.replace(rejectedDate.toString(), acceptedDate.toString());
            mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/client")
                            .header("Authorization", "Bearer " + sales)
                            .header("If-Match", draft.getResponse().getHeader("ETag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(acceptedClientRequest))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestedDeliveryDate").value(acceptedDate.toString()));

            setLeadDays(owner, settingsPath, 7);
            assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?",
                    String.class, requestUuid)).isEqualTo("SUBMITTED");
            assertThat(tenantJdbc().queryForObject("select requested_delivery_date from sales.purchase_request where id=?",
                    LocalDate.class, requestUuid)).isEqualTo(acceptedDate);
            var converted = convert(new PurchaseRequestResource(requestId,
                    approved.getResponse().getHeader("ETag"), sales), "lead-time-convert-" + uuid());
            assertThat(converted.id()).isNotBlank();
            assertThat(tenantJdbc().queryForObject("select status from sales.purchase_request where id=?",
                    String.class, requestUuid)).isEqualTo("CONVERTED");
            assertThat(tenantJdbc().queryForObject("select requested_delivery_date from sales.sales_order where id=?",
                    LocalDate.class, UUID.fromString(converted.id()))).isEqualTo(acceptedDate);
        } finally {
            setLeadDays(owner, settingsPath, originalLeadDays);
        }
    }

    @Test
    void directOrderPolicyAndCreateUseSameWorkspaceLeadTimeAndExplicitPermission() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String settingsPath = "/api/v1/workspaces/" + workspaceId() + "/operational-settings";
        MvcResult originalSettings = mockMvc.perform(get(settingsPath)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        int originalLeadDays = json(originalSettings).path("minimumDeliveryLeadDays").asInt();
        LocalDate today = LocalDate.now(Clock.systemUTC());
        LocalDate rejectedDate = today.plusDays(4);
        LocalDate earliestDate = today.plusDays(5);
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");

        try {
            setLeadDays(owner, settingsPath, 5);

            mockMvc.perform(get("/api/v1/direct-orders/delivery-policy")
                            .header("Authorization", "Bearer " + sales))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(5))
                    .andExpect(jsonPath("$.earliestAllowedDeliveryDate").value(earliestDate.toString()));

            mockMvc.perform(get("/api/v1/direct-orders/delivery-policy")
                            .header("Authorization", "Bearer " + buyer))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/v1/direct-orders")
                            .header("Authorization", "Bearer " + sales)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(directOrderBody(earliestDate)))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/api/v1/direct-orders")
                            .header("Authorization", "Bearer " + sales)
                            .header("Idempotency-Key", "lead-time-too-early-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(directOrderBody(rejectedDate)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.earliestAllowedDeliveryDate").value(earliestDate.toString()))
                    .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(5));

            mockMvc.perform(post("/api/v1/direct-orders")
                            .header("Authorization", "Bearer " + sales)
                            .header("Idempotency-Key", "lead-time-invalid-client-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(directOrderBody(earliestDate)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            mockMvc.perform(post("/api/v1/direct-orders")
                            .header("Authorization", "Bearer " + owner)
                            .header("Idempotency-Key", "lead-time-owner-denied-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(directOrderBody(earliestDate)))
                    .andExpect(status().isForbidden());
        } finally {
            setLeadDays(owner, settingsPath, originalLeadDays);
        }
    }

    private MvcResult setLeadDays(String owner, String settingsPath, int days) throws Exception {
        MvcResult current = mockMvc.perform(get(settingsPath).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        String currentEtag = current.getResponse().getHeader("ETag");
        assertThat(currentEtag).isNotBlank();
        MvcResult updated = mockMvc.perform(patch(settingsPath)
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", currentEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimumDeliveryLeadDays\":" + days + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(days))
                .andReturn();
        assertThat(updated.getResponse().getHeader("ETag")).isNotBlank();
        return updated;
    }

    private void assertRoleCanManageLeadTime(String roleCode, String existingToken, String settingsPath, int expectedDays) throws Exception {
        String token = existingToken != null
                ? existingToken
                : accessToken(createWorkspaceMemberWithRole(roleCode), "PLATFORM");
        MvcResult session = mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(session).at("/membership/roles").toString()).contains(roleCode);

        MvcResult current = mockMvc.perform(get(settingsPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(expectedDays))
                .andReturn();
        String etag = current.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        MvcResult updated = mockMvc.perform(patch(settingsPath)
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimumDeliveryLeadDays\":" + expectedDays + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumDeliveryLeadDays").value(expectedDays))
                .andReturn();
        assertThat(updated.getResponse().getHeader("ETag")).isNotBlank().isNotEqualTo(etag);
    }

    private String createWorkspaceMemberWithRole(String roleCode) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "lead-time-" + roleCode.toLowerCase(java.util.Locale.ROOT) + "-" + suffix + "@icisa-test.local";
        String username = "lead_time_" + suffix;
        assertThat(jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,"
                        + "display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, "Lead time " + roleCode))
                .as("one test user account is created")
                .isEqualTo(1);
        assertThat(jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) "
                        + "values (?,?, 'bcrypt',current_timestamp)",
                userId, PASSWORD_ENCODER.encode(TEST_PASSWORD)))
                .as("one credential for the test password is created")
                .isEqualTo(1);
        assertThat(jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'INTERNAL','ACTIVE',current_timestamp,current_timestamp,0)",
                memberId, tenant, workspace, userId))
                .as("one active Workspace membership is created")
                .isEqualTo(1);
        assertThat(jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,r.id,current_timestamp from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.code=? on conflict do nothing",
                memberId, tenant, workspace, roleCode.toLowerCase(java.util.Locale.ROOT)))
                .as("one built-in %s role is assigned", roleCode)
                .isEqualTo(1);
        jdbc.update("insert into tenant_management.membership_authorization_state "
                        + "(membership_id,tenant_id,workspace_id,authorization_version,updated_at) "
                        + "values (?,?,?,0,current_timestamp) on conflict (membership_id) do nothing",
                memberId, tenant, workspace);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m "
                        + "join tenant_management.membership_role_definition a on a.membership_id=m.id "
                        + "join tenant_management.role_definition r on r.id=a.role_id "
                        + "where m.id=? and m.tenant_id=? and m.workspace_id=? and m.membership_type='INTERNAL' "
                        + "and m.status='ACTIVE' and r.tenant_id is null and r.status='ACTIVE' and r.code=?",
                Integer.class, memberId, tenant, workspace, roleCode.toLowerCase(java.util.Locale.ROOT)))
                .as("the active Workspace membership has exactly the requested built-in role")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.membership_authorization_state "
                        + "where membership_id=? and tenant_id=? and workspace_id=?",
                Integer.class, memberId, tenant, workspace))
                .as("authorization state is recorded for the exact Tenant and Workspace")
                .isEqualTo(1);
        return email;
    }

    private static String purchaseRequestBody(LocalDate date) {
        return "{\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"" + date + "\","
                + "\"deliveryProfileSnapshot\":\"Lead time HTTP integration\",\"paymentOption\":\"CASH\","
                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}";
    }

    private static String directOrderBody(LocalDate date) {
        return "{\"clientAccountId\":\"not-a-uuid\",\"priority\":\"NORMAL\","
                + "\"requestedDeliveryDate\":\"" + date + "\",\"deliveryProfileSnapshot\":\"Lead time HTTP integration\","
                + "\"paymentOption\":\"CASH\",\"comment\":\"Lead time HTTP integration\","
                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}";
    }
}
