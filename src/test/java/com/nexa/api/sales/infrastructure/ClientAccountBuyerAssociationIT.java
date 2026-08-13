package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ClientAccountBuyerAssociationIT extends PostgresIntegrationSupport {
    @Test
    void concurrentBuyerAssociationsLeaveOneRelationshipAndReturnOneConflict() throws Exception {
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult account = mockMvc.perform(post("/api/v1/client-accounts")
                        .header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"RACE-" + uuid().substring(0, 8).toUpperCase() + "\",\"businessName\":\"Race Buyer SAC\",\"commercialName\":\"Race Buyer\",\"countryCode\":\"PE\",\"taxType\":\"RUC\",\"taxValue\":\"20" + uniqueDigits() + "\",\"segment\":\"Restaurant\",\"contactPerson\":\"Race Contact\",\"contactEmail\":\"race-" + uuid().substring(0, 8) + "@example.test\",\"phone\":\"+51999999999\",\"deliveryProfile\":\"Cold chain receiving\",\"paymentCondition\":\"cash\"}"))
                .andExpect(status().isCreated()).andReturn();
        String accountId = json(account).get("id").asText();

        UUID firstBuyer = createBuyerMembership("race-first-" + uuid().substring(0, 8) + "@example.test");
        UUID secondBuyer = createBuyerMembership("race-second-" + uuid().substring(0, 8) + "@example.test");
        String etag = account.getResponse().getHeader("ETag");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> awaitAndAssociate(start, sales, accountId, firstBuyer, etag),
                    () -> awaitAndAssociate(start, sales, accountId, secondBuyer, etag)
            );
            List<Future<Integer>> futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            List<Integer> statuses = futures.stream().map(future -> {
                try { return future.get(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=?", Integer.class,
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(accountId))).isEqualTo(1);
    }

    private int associate(String token, String accountId, UUID membershipId, String etag) throws Exception {
        return mockMvc.perform(put("/api/v1/client-accounts/" + accountId + "/buyer-membership")
                .header("Authorization", "Bearer " + token).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"membershipId\":\"" + membershipId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int awaitAndAssociate(CountDownLatch start, String token, String accountId, UUID membershipId, String etag) throws Exception {
        start.await();
        return associate(token, accountId, membershipId, etag);
    }

    private UUID createBuyerMembership(String email) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String username = "race_" + userId.toString().replace("-", "").substring(0, 16);
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, "Race Buyer", "en");
        jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'BUYER','ACTIVE',current_timestamp,current_timestamp,0)",
                membershipId, UUID.fromString(workspaceId()), userId);
        return membershipId;
    }

    private String uniqueDigits() {
        return String.format(Locale.ROOT, "%09d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }
}
