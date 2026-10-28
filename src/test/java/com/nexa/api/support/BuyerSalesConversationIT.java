package com.nexa.api.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerSalesConversationIT extends PostgresIntegrationSupport {
    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void eachBuyerMembershipSeesOnlyMessagesAddressedToItAndRevocationRemovesAccess() throws Exception {
        String salesToken = accessToken(SALES_EMAIL, "PLATFORM");
        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        UUID firstBuyerMembershipId = createBuyerMembership("chat-first-" + uuid().substring(0, 8));
        UUID salesMembershipId = UUID.fromString(membershipId(SALES_EMAIL));
        UUID accountId = createChatAccount(tenantId, workspaceId, salesMembershipId);
        associateBuyer(tenantId, workspaceId, accountId, firstBuyerMembershipId);
        String firstBuyerToken = accessToken(buyerEmail(firstBuyerMembershipId), "PORTAL");

        String buyerMessage = "Please confirm the delivery window";
        var created = mockMvc.perform(post("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken)
                        .header("Idempotency-Key", "buyer-window-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"body\":\"" + buyerMessage + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderKind").value("BUYER"))
                .andExpect(jsonPath("$.buyerMembershipId").value(firstBuyerMembershipId.toString()))
                .andReturn();
        JsonNode buyerMessageResponse = JSON.readTree(created.getResponse().getContentAsString());
        String buyerMessageId = buyerMessageResponse.path("id").asText();

        String idempotencyKey = "buyer-retry-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"body\":\"retry body\"}"))
                .andExpect(status().isCreated());
        var retry = mockMvc.perform(post("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"body\":\"retry body\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String retriedMessageId = JSON.readTree(retry.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(post("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"body\":\"different body\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        var salesPage = mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        JsonNode initialSalesMessages = JSON.readTree(salesPage.getResponse().getContentAsString()).path("items");
        org.assertj.core.api.Assertions.assertThat(initialSalesMessages.toString()).contains(buyerMessageId,
                retriedMessageId);

        jdbc.update("update tenant_management.workspace_membership set status='SUSPENDED',version=version+1 "
                + "where tenant_id=? and workspace_id=? and id=?", tenantId, workspaceId, firstBuyerMembershipId);
        mockMvc.perform(get("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyerToken))
                .andExpect(status().is4xxClientError());

        UUID nextBuyerMembershipId = createBuyerMembership("chat-next-" + uuid().substring(0, 8));
        associateBuyer(tenantId, workspaceId, accountId, nextBuyerMembershipId);
        String nextBuyerToken = accessToken(buyerEmail(nextBuyerMembershipId), "PORTAL");
        mockMvc.perform(get("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nextBuyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        String salesMessageBody = "Delivery is scheduled for tomorrow";
        var salesCreated = mockMvc.perform(post("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                        .header("Idempotency-Key", "sales-window-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"recipientBuyerMembershipId\":\"" + nextBuyerMembershipId
                                + "\",\"body\":\"" + salesMessageBody + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderKind").value("SALES"))
                .andExpect(jsonPath("$.buyerMembershipId").value(nextBuyerMembershipId.toString()))
                .andReturn();
        String salesMessageId = JSON.readTree(salesCreated.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(get("/api/v1/buyer/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nextBuyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(salesMessageId))
                .andExpect(jsonPath("$.items[0].senderKind").value("SALES"))
                .andExpect(jsonPath("$.items[0].buyerMembershipId").value(nextBuyerMembershipId.toString()));

        mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void buyerCannotChooseAccountAndSalesCannotReadAnotherOwnerAccount() throws Exception {
        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        String salesToken = accessToken(SALES_EMAIL, "PLATFORM");
        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        UUID accountId = UUID.fromString(buyerClientAccountId());
        UUID previousOwner = tenantJdbc().query("select sales_owner_membership_id from sales.client_account "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, workspaceId, accountId);
        assignSalesOwner(tenantId, workspaceId, accountId, UUID.randomUUID());

        try {
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/client-accounts/{clientAccountId}/messages", accountId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                            .header("Idempotency-Key", "unassigned-sales-message")
                            .contentType("application/json")
                            .content("{\"recipientBuyerMembershipId\":\"" + UUID.randomUUID()
                                    + "\",\"body\":\"must not be sent\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            assignSalesOwner(tenantId, workspaceId, accountId, previousOwner);
        }
    }

    private void assignSalesOwner(UUID tenantId, UUID workspaceId, UUID accountId, UUID ownerMembershipId) {
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=?",
                ownerMembershipId, tenantId, workspaceId, accountId);
    }

    private UUID createBuyerMembership(String usernameSeed) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = usernameSeed + "-" + suffix + "@icisa-test.local";
        String username = "chat_" + suffix;
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,"
                        + "display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, "Chat Buyer " + suffix);
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) "
                        + "select ?,password_hash,algorithm,changed_at from iam.password_credential where user_id=?",
                userId, UUID.fromString(membershipUserId(BUYER_EMAIL)));
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'BUYER','ACTIVE',current_timestamp,current_timestamp,0)",
                memberId, tenant, workspace, userId);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,r.id,current_timestamp from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.code='buyer' on conflict do nothing",
                memberId, tenant, workspace);
        jdbc.update("insert into tenant_management.membership_authorization_state "
                        + "(membership_id,tenant_id,workspace_id,authorization_version,updated_at) "
                        + "values (?,?,?,0,current_timestamp) on conflict (membership_id) do nothing",
                memberId, tenant, workspace);
        return memberId;
    }

    private String buyerEmail(UUID membershipId) {
        return jdbc.queryForObject("select u.email from iam.user_account u "
                        + "join tenant_management.workspace_membership m on m.user_id=u.id where m.id=?",
                String.class, membershipId);
    }

    private String membershipUserId(String email) {
        return jdbc.queryForObject("select user_id::text from tenant_management.workspace_membership m "
                        + "join iam.user_account u on u.id=m.user_id where u.normalized_email=? and m.workspace_id=?",
                String.class, email, UUID.fromString(workspaceId()));
    }

    private UUID createChatAccount(UUID tenantId, UUID workspaceId, UUID salesMembershipId) {
        UUID accountId = UUID.randomUUID();
        String code = "CHAT-" + accountId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String taxIdentifier = "20" + String.format(Locale.ROOT, "%09d",
                Math.floorMod(accountId.getMostSignificantBits(), 1_000_000_000L));
        tenantJdbc().update("insert into sales.client_account "
                        + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                        + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,sales_owner_membership_id,account_type,"
                        + "created_at,updated_at,version) "
                        + "values (?,?,?,?,?,'Chat Company','PE','RUC',?,'Integration','Chat Contact',"
                        + "?,'+51999999999','Local integration fixture','cash','ACTIVE',?,'BUSINESS',"
                        + "current_timestamp,current_timestamp,0)", accountId, tenantId, workspaceId, code,
                "Chat Company " + code, taxIdentifier, "chat-" + code.toLowerCase(Locale.ROOT) + "@example.test",
                salesMembershipId);
        return accountId;
    }

    private void associateBuyer(UUID tenantId, UUID workspaceId, UUID accountId, UUID buyerMembershipId) {
        tenantJdbc().update("insert into sales.client_account_membership "
                        + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
                        + "values (?,?,?,?,current_timestamp)",
                accountId, buyerMembershipId, tenantId, workspaceId);
    }
}
