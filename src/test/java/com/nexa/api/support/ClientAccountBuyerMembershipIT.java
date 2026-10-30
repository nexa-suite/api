package com.nexa.api.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ClientAccountBuyerMembershipIT extends PostgresIntegrationSupport {
    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void businessAccountSupportsMultipleBuyerMembershipsAndSoftDeactivation() throws Exception {
        String salesToken = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult createdAccount = createBusinessAccount(salesToken);
        JsonNode account = JSON.readTree(createdAccount.getResponse().getContentAsString());
        String accountId = account.path("id").asText();
        String accountVersion = createdAccount.getResponse().getHeader(HttpHeaders.ETAG);
        Buyer buyerA = createBuyer("account-a");
        Buyer buyerB = createBuyer("account-b");

        MvcResult associatedA = associate(salesToken, accountId, buyerA.membershipId(), accountVersion)
                .andExpect(status().isCreated()).andReturn();
        MvcResult associatedB = associate(salesToken, accountId, buyerB.membershipId(),
                associatedA.getResponse().getHeader(HttpHeaders.ETAG)).andExpect(status().isCreated()).andReturn();
        MvcResult duplicateA = associate(salesToken, accountId, buyerA.membershipId(),
                associatedA.getResponse().getHeader(HttpHeaders.ETAG)).andExpect(status().isOk()).andReturn();
        assertThat(duplicateA.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo(associatedB.getResponse().getHeader(HttpHeaders.ETAG));

        mockMvc.perform(get("/api/v1/client-accounts/{id}/buyer-memberships", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].email").isNotEmpty())
                .andExpect(jsonPath("$.items[1].membershipId").isNotEmpty());

        String tokenA = accessToken(buyerA.email(), "PORTAL");
        String tokenB = accessToken(buyerB.email(), "PORTAL");
        mockMvc.perform(get("/api/v1/client-accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));
        mockMvc.perform(get("/api/v1/client-accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));

        MvcResult deactivated = mockMvc.perform(delete("/api/v1/client-accounts/{id}/buyer-memberships/{membershipId}",
                                accountId, buyerA.membershipId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                        .header(HttpHeaders.IF_MATCH, associatedB.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn();
        mockMvc.perform(delete("/api/v1/client-accounts/{id}/buyer-memberships/{membershipId}",
                                accountId, buyerA.membershipId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                        .header(HttpHeaders.IF_MATCH, associatedB.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/client-accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/client-accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));

        MvcResult reactivated = associate(salesToken, accountId, buyerA.membershipId(),
                deactivated.getResponse().getHeader(HttpHeaders.ETAG)).andExpect(status().isOk()).andReturn();
        assertThat(reactivated.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank();
        mockMvc.perform(get("/api/v1/client-accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));

        assertThat(tenantJdbc().queryForObject("select count(*) from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and client_account_id=?", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(accountId))).isEqualTo(2);
        assertThat(tenantJdbc().queryForObject("select status from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(accountId),
                buyerA.membershipId())).isEqualTo("ACTIVE");
        assertThat(tenantJdbc().queryForObject("select has_table_privilege('nexa_runtime', "
                        + "'sales.client_account_membership', 'DELETE')", Boolean.class)).isFalse();
    }

    @Test
    void buyerSelfViewDoesNotExposeFirstBuyersPersonalContactToOtherBusinessMembers() throws Exception {
        Buyer buyerA = createBuyer("account-contact-a");
        Buyer buyerB = createBuyer("account-contact-b");
        String buyerAToken = accessToken(buyerA.email(), "PORTAL");
        String phone = "+51987654321";
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String businessName = "Buyer Company " + suffix;
        String taxValue = "20" + String.format(Locale.ROOT, "%09d",
                Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));

        String registrationPayload = "{\"accountType\":\"BUSINESS\",\"businessName\":\"" + businessName
                + "\",\"commercialName\":\"Buyer Co " + suffix + "\",\"taxValue\":\""
                + taxValue + "\",\"phone\":\"" + phone + "\"}";
        MvcResult registration = mockMvc.perform(post("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactPerson").value(buyerA.displayName()))
                .andExpect(jsonPath("$.contactEmail").value(buyerA.email()))
                .andExpect(jsonPath("$.phone").value(phone))
                .andReturn();
        String accountId = JSON.readTree(registration.getResponse().getContentAsString()).path("id").asText();
        mockMvc.perform(post("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone));
        mockMvc.perform(post("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload.replace(phone, "+51987654322")))
                .andExpect(status().isConflict());
        assertThat(tenantJdbc().queryForObject("select phone from sales.client_account "
                        + "where tenant_id=? and workspace_id=? and id=?", String.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(accountId)))
                .isEqualTo(phone);
        mockMvc.perform(get("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPerson").doesNotExist())
                .andExpect(jsonPath("$.contactEmail").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.buyerMembershipId").doesNotExist());
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult associatedBuyerB = associate(ownerToken, accountId, buyerB.membershipId(),
                registration.getResponse().getHeader(HttpHeaders.ETAG)).andExpect(status().isCreated()).andReturn();

        for (Buyer buyer : new Buyer[] { buyerA, buyerB }) {
            MvcResult buyerAccount = mockMvc.perform(get("/api/v1/client-accounts/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(buyer.email(), "PORTAL")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(accountId))
                    .andExpect(jsonPath("$.businessName").value(businessName))
                    .andReturn();
            JsonNode buyerView = JSON.readTree(buyerAccount.getResponse().getContentAsString());
            assertThat(buyerView.has("contactPerson")).isFalse();
            assertThat(buyerView.has("contactEmail")).isFalse();
            assertThat(buyerView.has("phone")).isFalse();
            assertThat(buyerView.has("buyerMembershipId")).isFalse();
        }

        mockMvc.perform(delete("/api/v1/client-accounts/{id}/buyer-memberships/{membershipId}",
                                accountId, buyerB.membershipId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, associatedBuyerB.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isOk());
        MvcResult remainingBuyerView = mockMvc.perform(get("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerAToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode remainingBuyer = JSON.readTree(remainingBuyerView.getResponse().getContentAsString());
        assertThat(remainingBuyer.has("contactPerson")).isFalse();
        assertThat(remainingBuyer.has("contactEmail")).isFalse();
        assertThat(remainingBuyer.has("phone")).isFalse();
        mockMvc.perform(get("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(buyerB.email(), "PORTAL")))
                .andExpect(status().isNotFound());
    }

    @Test
    void individualAccountRejectsSecondBuyerMembership() throws Exception {
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        Buyer individual = createBuyer("individual-owner");
        Buyer secondBuyer = createBuyer("individual-second");
        String individualToken = accessToken(individual.email(), "PORTAL");
        MvcResult individualAccount = mockMvc.perform(post("/api/v1/client-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + individualToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"INDIVIDUAL\",\"phone\":\"+51999999999\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode account = JSON.readTree(individualAccount.getResponse().getContentAsString());
        String accountId = account.path("id").asText();

        mockMvc.perform(post("/api/v1/client-accounts/{id}/buyer-memberships", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, individualAccount.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + secondBuyer.membershipId() + "\"}"))
                .andExpect(status().isConflict());

        assertThat(tenantJdbc().queryForObject("select count(*) from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and client_account_id=?", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(accountId))).isEqualTo(1);
        assertThatThrownBy(() -> tenantJdbc().update("insert into sales.client_account_membership "
                        + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
                        + "values (?,?,?,?,current_timestamp)", UUID.fromString(accountId), secondBuyer.membershipId(),
                UUID.fromString(tenantId()), UUID.fromString(workspaceId())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("uq_client_account_individual_buyer");
    }

    private org.springframework.test.web.servlet.ResultActions associate(String token, String accountId,
            UUID membershipId, String etag) throws Exception {
        return mockMvc.perform(post("/api/v1/client-accounts/{id}/buyer-memberships", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipId\":\"" + membershipId + "\"}"));
    }

    private MvcResult createBusinessAccount(String salesToken) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        String tax = "20" + String.format(Locale.ROOT, "%09d",
                Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
        return mockMvc.perform(post("/api/v1/client-accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"MB-" + suffix + "\",\"businessName\":\"Membership Company " + suffix
                                + "\",\"commercialName\":\"Membership " + suffix
                                + "\",\"countryCode\":\"PE\",\"taxType\":\"RUC\",\"taxValue\":\"" + tax
                                + "\",\"segment\":\"Restaurant\",\"contactPerson\":\"Test Contact\","
                                + "\"contactEmail\":\"buyer-" + suffix.toLowerCase(Locale.ROOT)
                                + "@example.test\",\"phone\":\"+51999999999\",\"deliveryProfile\":\"Cold chain\","
                                + "\"paymentCondition\":\"cash\"}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private Buyer createBuyer(String prefix) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = prefix + "-" + suffix + "@icisa-test.local";
        String username = "member_" + suffix;
        String displayName = "Buyer " + suffix;
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,"
                        + "display_name,preferred_language,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,'es','ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, displayName);
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) "
                        + "select ?,password_hash,algorithm,changed_at from iam.password_credential where user_id=?",
                userId, UUID.fromString(membershipUserId(BUYER_EMAIL)));
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'BUYER','ACTIVE',current_timestamp,current_timestamp,0)",
                membershipId, tenant, workspace, userId);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,r.id,current_timestamp from tenant_management.role_definition r "
                        + "where r.tenant_id is null and r.code='buyer' on conflict do nothing",
                membershipId, tenant, workspace);
        jdbc.update("insert into tenant_management.membership_authorization_state "
                        + "(membership_id,tenant_id,workspace_id,authorization_version,updated_at) "
                        + "values (?,?,?,0,current_timestamp) on conflict (membership_id) do nothing",
                membershipId, tenant, workspace);
        return new Buyer(membershipId, email, displayName);
    }

    private String membershipUserId(String email) {
        return jdbc.queryForObject("select user_id::text from tenant_management.workspace_membership m "
                        + "join iam.user_account u on u.id=m.user_id where u.normalized_email=? and m.workspace_id=?",
                String.class, email, UUID.fromString(workspaceId()));
    }

    private record Buyer(UUID membershipId, String email, String displayName) { }
}
