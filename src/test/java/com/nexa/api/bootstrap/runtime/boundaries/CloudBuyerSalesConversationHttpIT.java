package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the cloud message opt-in against the isolated ICISA Tenant fixture database. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.purchase-request.enabled=false",
        "nexa.tenant-business.cloud-buyer-sales-messaging.enabled=true"
})
class CloudBuyerSalesConversationHttpIT extends PostgresIntegrationSupport {
    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void buyerSendsSalesReadsAndRepliesAndBuyerSeesTheScopedConversation() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.fromString(buyerClientAccountId());
        UUID buyerMembership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID salesMembership = UUID.fromString(membershipId(SALES_EMAIL));
        UUID previousOwner = tenantJdbc().queryForObject(
                "select sales_owner_membership_id from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                UUID.class, tenant, workspace, account);
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=?",
                salesMembership, tenant, workspace, account);

        assertThat(tenantJdbc().queryForObject("""
                select has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'SELECT')
                   and has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'INSERT')
                   and not has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'UPDATE')
                   and not has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'DELETE')
                """, Boolean.class)).isTrue();

        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String sales = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        String suffix = uuid().substring(0, 8);
        String buyerBody = "Cloud Buyer message " + suffix;
        String salesBody = "Cloud Sales reply " + suffix;

        try {
            var buyerCreated = mockMvc.perform(post("/api/v1/buyer/messages")
                            .header(HttpHeaders.AUTHORIZATION, buyer)
                            .header("Idempotency-Key", "cloud-buyer-message-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"" + buyerBody + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.senderKind").value("BUYER"))
                    .andExpect(jsonPath("$.buyerMembershipId").value(buyerMembership.toString()))
                    .andReturn();
            String buyerMessageId = JSON.readTree(buyerCreated.getResponse().getContentAsString()).path("id").asText();

            var salesRead = mockMvc.perform(get("/api/v1/client-accounts/{id}/messages", account)
                            .header(HttpHeaders.AUTHORIZATION, sales))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(salesRead.getResponse().getContentAsString()).contains(buyerMessageId, buyerBody, "BUYER");

            var salesCreated = mockMvc.perform(post("/api/v1/client-accounts/{id}/messages", account)
                            .header(HttpHeaders.AUTHORIZATION, sales)
                            .header("Idempotency-Key", "cloud-sales-message-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientBuyerMembershipId\":\"" + buyerMembership
                                    + "\",\"body\":\"" + salesBody + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.senderKind").value("SALES"))
                    .andExpect(jsonPath("$.buyerMembershipId").value(buyerMembership.toString()))
                    .andReturn();
            String salesMessageId = JSON.readTree(salesCreated.getResponse().getContentAsString()).path("id").asText();

            var buyerRead = mockMvc.perform(get("/api/v1/buyer/messages")
                            .header(HttpHeaders.AUTHORIZATION, buyer))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(buyerRead.getResponse().getContentAsString())
                    .contains(buyerMessageId, buyerBody, salesMessageId, salesBody, "SALES");

            mockMvc.perform(get("/api/v1/client-accounts/{id}/messages", account)
                            .header(HttpHeaders.AUTHORIZATION, buyer))
                    .andExpect(status().isForbidden());

            tenantJdbc().update("update sales.client_account set sales_owner_membership_id=?,version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    UUID.fromString(membershipId(OWNER_EMAIL)), tenant, workspace, account);
            mockMvc.perform(get("/api/v1/client-accounts/{id}/messages", account)
                            .header(HttpHeaders.AUTHORIZATION, sales))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/client-accounts/{id}/messages", account)
                            .header(HttpHeaders.AUTHORIZATION, sales)
                            .header("Idempotency-Key", "cloud-unassigned-sales-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientBuyerMembershipId\":\"" + buyerMembership
                                    + "\",\"body\":\"must not be sent\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            tenantJdbc().update("update sales.client_account set sales_owner_membership_id=?,version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    previousOwner, tenant, workspace, account);
        }
    }
}
