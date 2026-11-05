package com.nexa.api.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP integration coverage for membership-owned Buyer delivery addresses. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.tenant-business.purchase-request.enabled=true")
class BuyerPersonalAddressIT extends NexaWorkflowIntegrationSupport {

    @Test
    void buyerAddressBookIsMembershipScopedAndWorkforceReadIsTenantAndAssignmentScoped() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        List<Identity> identities = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        try {
            Identity buyerA = createMembership("buyer", "BUYER", identities);
            Identity buyerB = createMembership("buyer", "BUYER", identities);
            Identity buyerOtherAccount = createMembership("buyer", "BUYER", identities);
            Identity unassignedSales = createMembership("sales", "INTERNAL", identities);
            Identity bom = createMembership("business_operations_manager", "INTERNAL", identities);

            UUID assignedSalesId = UUID.fromString(membershipId(SALES_EMAIL));
            UUID accountA = createBusinessAccount(tenant, workspace, assignedSalesId);
            UUID accountB = createBusinessAccount(tenant, workspace, assignedSalesId);
            accounts.addAll(List.of(accountA, accountB));
            linkBuyer(tenant, workspace, accountA, buyerA.membershipId());
            linkBuyer(tenant, workspace, accountA, buyerB.membershipId());
            linkBuyer(tenant, workspace, accountB, buyerOtherAccount.membershipId());

            String buyerAToken = accessToken(buyerA.email(), "PORTAL");
            String buyerBToken = accessToken(buyerB.email(), "PORTAL");
            String assignedSalesToken = accessToken(SALES_EMAIL, "PLATFORM");
            String unassignedSalesToken = accessToken(unassignedSales.email(), "PLATFORM");
            String bomToken = accessToken(bom.email(), "PLATFORM");
            String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");

            var createdDefault = mockMvc.perform(post("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addressRequest("Home", "Av. Synthetic 100", true)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.label").value("Home"))
                    .andExpect(jsonPath("$.addressType").value("STREET"))
                    .andExpect(jsonPath("$.roadType").value("AVENUE"))
                    .andExpect(jsonPath("$.line").value("Av. Synthetic 100"))
                    .andExpect(jsonPath("$.recipientName").value("Buyer A"))
                    .andExpect(jsonPath("$.districtCode").value("150101"))
                    .andExpect(jsonPath("$.defaultAddress").value(true))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn();
            String addressId = json(createdDefault).path("id").asText();
            String defaultEtag = createdDefault.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(defaultEtag).isEqualTo("\"1\"");

            var createdSecond = mockMvc.perform(post("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addressRequest("Receiving", "Jr. Synthetic 200", false)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.defaultAddress").value(false))
                    .andExpect(jsonPath("$.version").value(0))
                    .andReturn();
            String secondAddressId = json(createdSecond).path("id").asText();
            assertThat(createdSecond.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"");

            mockMvc.perform(get("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerBToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            String changeBody = updateRequest("Taken over", "Calle Synthetic 300");
            mockMvc.perform(patch("/api/v1/buyer/addresses/{addressId}", addressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerBToken))
                            .header(HttpHeaders.IF_MATCH, defaultEtag)
                            .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                    .andExpect(status().isNotFound());
            mockMvc.perform(put("/api/v1/buyer/addresses/{addressId}/default", addressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerBToken))
                            .header(HttpHeaders.IF_MATCH, defaultEtag))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/v1/buyer/addresses/{addressId}", addressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerBToken))
                            .header(HttpHeaders.IF_MATCH, defaultEtag))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(assignedSalesToken)))
                    .andExpect(status().isForbidden());

            var selectedDefault = mockMvc.perform(put("/api/v1/buyer/addresses/{addressId}/default", secondAddressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .header(HttpHeaders.IF_MATCH, "\"0\""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultAddress").value(true))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn();
            String selectedEtag = selectedDefault.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(selectedEtag).isEqualTo("\"1\"");

            mockMvc.perform(patch("/api/v1/buyer/addresses/{addressId}", secondAddressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                    .andExpect(status().isPreconditionRequired());
            mockMvc.perform(patch("/api/v1/buyer/addresses/{addressId}", secondAddressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                    .andExpect(status().isConflict());

            var updated = mockMvc.perform(patch("/api/v1/buyer/addresses/{addressId}", secondAddressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .header(HttpHeaders.IF_MATCH, selectedEtag)
                            .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("Taken over"))
                    .andExpect(jsonPath("$.addressType").value("STREET"))
                    .andExpect(jsonPath("$.roadType").value("AVENUE"))
                    .andExpect(jsonPath("$.line").value("Calle Synthetic 300"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andReturn();
            String updatedEtag = updated.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(updatedEtag).isEqualTo("\"2\"");

            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(assignedSalesToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(bomToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(unassignedSalesToken)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerOtherAccount.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(assignedSalesToken)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            UUID.randomUUID(), buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(assignedSalesToken)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses",
                            accountA, buyerA.membershipId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken)))
                    .andExpect(status().isForbidden());

            var deactivated = mockMvc.perform(delete("/api/v1/buyer/addresses/{addressId}", secondAddressId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken))
                            .header(HttpHeaders.IF_MATCH, updatedEtag))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false))
                    .andExpect(jsonPath("$.defaultAddress").value(false))
                    .andExpect(jsonPath("$.version").value(3))
                    .andReturn();
            assertThat(deactivated.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"3\"");
            mockMvc.perform(get("/api/v1/buyer/addresses")
                            .header(HttpHeaders.AUTHORIZATION, bearer(buyerAToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(addressId));

            assertThat(tenantJdbc().queryForObject("select count(*) from sales.buyer_address where tenant_id=? "
                            + "and workspace_id=? and buyer_membership_id=?", Integer.class,
                    tenant, workspace, buyerA.membershipId())).isEqualTo(2);
            assertThat(tenantJdbc().queryForObject("select status from sales.buyer_address where tenant_id=? "
                            + "and workspace_id=? and buyer_membership_id=? and id=?", String.class,
                    tenant, workspace, buyerA.membershipId(), UUID.fromString(secondAddressId))).isEqualTo("INACTIVE");
            assertThat(tenantJdbc().queryForObject("select count(*) from sales.buyer_address where tenant_id=? "
                            + "and workspace_id=? and buyer_membership_id=? and default_address and status='ACTIVE'",
                    Integer.class, tenant, workspace, buyerA.membershipId())).isZero();
        } finally {
            cleanup(tenant, workspace, accounts, identities);
        }
    }

    private Identity createMembership(String roleCode, String membershipType, List<Identity> identities) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String email = roleCode.replace('_', '-') + "-address-" + suffix + "@icisa-test.local";
        String username = "address_" + suffix;
        Identity identity = new Identity(userId, memberId, email);
        identities.add(identity);
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,"
                        + "display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, "Synthetic " + roleCode);
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) "
                        + "select ?,password_hash,algorithm,changed_at from iam.password_credential where user_id=?",
                userId, UUID.fromString(membershipUserId(BUYER_EMAIL)));
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?, ?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                memberId, tenant, workspace, userId, membershipType);
        assertThat(jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id,assigned_at) "
                        + "select ?,?,?,r.id,?,current_timestamp from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.workspace_id is null and r.code=?",
                memberId, tenant, workspace, UUID.fromString(membershipId(OWNER_EMAIL)), roleCode))
                .as("synthetic member must receive existing %s role", roleCode).isEqualTo(1);
        return identity;
    }

    private UUID createBusinessAccount(UUID tenant, UUID workspace, UUID salesMembershipId) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "").substring(0, 9);
        String taxNumber = "20" + String.format(Locale.ROOT, "%09d",
                Math.floorMod(id.getMostSignificantBits(), 1_000_000_000L));
        tenantJdbc().update("insert into sales.client_account "
                        + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                        + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,sales_owner_membership_id,account_type,"
                        + "created_at,updated_at,version) "
                        + "values (?,?,?,?,?,'Personal address integration','PE','RUC',?,'Integration',"
                        + "'Synthetic Contact',?,'+51999999999','Synthetic receiving','cash','ACTIVE',?,'BUSINESS',"
                        + "current_timestamp,current_timestamp,0)",
                id, tenant, workspace, "ADR-" + suffix, "Personal Address " + suffix, taxNumber,
                "address-" + suffix + "@example.test", salesMembershipId);
        return id;
    }

    private void linkBuyer(UUID tenant, UUID workspace, UUID accountId, UUID buyerMembershipId) {
        tenantJdbc().update("insert into sales.client_account_membership "
                        + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
                        + "values (?,?,?,?,current_timestamp)", accountId, buyerMembershipId, tenant, workspace);
    }

    private void cleanup(UUID tenant, UUID workspace, List<UUID> accounts, List<Identity> identities) {
        List<UUID> memberships = identities.stream().map(Identity::membershipId).toList();
        for (UUID membership : memberships) {
            tenantJdbc().update("delete from sales.buyer_address where tenant_id=? and workspace_id=? "
                    + "and buyer_membership_id=?", tenant, workspace, membership);
        }
        for (UUID account : accounts) {
            tenantJdbc().update("delete from sales.client_account_membership where tenant_id=? and workspace_id=? "
                    + "and client_account_id=?", tenant, workspace, account);
            tenantJdbc().update("delete from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, account);
        }
        for (Identity identity : identities) {
            jdbc.update("delete from iam.refresh_session where membership_id=?", identity.membershipId());
            jdbc.update("delete from tenant_management.membership_authorization_state where membership_id=?", identity.membershipId());
            jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", identity.membershipId());
            jdbc.update("delete from tenant_management.workspace_membership where id=?", identity.membershipId());
            jdbc.update("delete from iam.password_credential where user_id=?", identity.userId());
            jdbc.update("delete from iam.user_account where id=?", identity.userId());
        }
    }

    private String membershipUserId(String email) {
        return jdbc.queryForObject("select user_id::text from tenant_management.workspace_membership m "
                        + "join iam.user_account u on u.id=m.user_id where u.normalized_email=? and m.workspace_id=?",
                String.class, email, UUID.fromString(workspaceId()));
    }

    private static String addressRequest(String label, String line, boolean defaultAddress) {
        return "{\"label\":\"" + label + "\",\"defaultAddress\":" + defaultAddress
                + ",\"address\":{\"addressType\":\"STREET\",\"line\":\"" + line
                + "\",\"reference\":\"Near synthetic landmark\",\"countryCode\":\"PE\","
                + "\"departmentCode\":\"15\",\"provinceCode\":\"1501\",\"districtCode\":\"150101\","
                + "\"recipientName\":\"Buyer A\",\"recipientPhone\":\"+51999999991\","
                + "\"roadType\":\"AVENUE\",\"streetName\":\"Synthetic Avenue\","
                + "\"streetNumber\":\"100\",\"interior\":\"2B\",\"postalCode\":\"15001\","
                + "\"receivingInstructions\":\"Synthetic test instructions\","
                + "\"receivingHours\":\"09:00-17:00\",\"latitude\":-12.0464,\"longitude\":-77.0428,"
                + "\"placeId\":\"synthetic-place\",\"source\":\"MANUAL\"}}";
    }

    private static String updateRequest(String label, String line) {
        String createBody = addressRequest(label, line, false);
        int addressStart = createBody.indexOf("\"address\":");
        return "{\"label\":\"" + label + "\"," + createBody.substring(addressStart, createBody.length() - 1) + "}";
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private record Identity(UUID userId, UUID membershipId, String email) { }
}
