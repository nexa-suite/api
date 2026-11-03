package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.port.StripePaymentProvider.SignedProviderWebhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerPaymentSetupRevocationConcurrencyIT extends PaymentIntegrationSupport {
    private static final String BUYER_SETUP_AUTH_LOCK = "buyer-payment-setup-auth";

    @Autowired
    private StripePaymentProvider stripePaymentProvider;

    @AfterEach
    void restoreBuyerAccountAuthorizationState() {
        UUID tenantId = tenantUuid();
        UUID workspaceId = workspaceUuid();
        UUID clientAccountId = UUID.fromString(buyerClientAccountId());
        UUID membershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID buyerUserId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership "
                + "where id=? and tenant_id=? and workspace_id=?", UUID.class,
                membershipId, tenantId, workspaceId);
        jdbc.update("update tenant_management.tenant set status='ACTIVE',updated_at=current_timestamp,"
                        + "version=version+1 where id=?",
                tenantId);
        jdbc.update("update iam.user_account set status='ACTIVE',updated_at=current_timestamp,version=version+1 "
                        + "where id=?",
                buyerUserId);
        tenantJdbc().update("update sales.client_account set status='ACTIVE',updated_at=current_timestamp,"
                        + "version=version+1 where tenant_id=? and workspace_id=? and id=?",
                tenantId, workspaceId, clientAccountId);
        tenantJdbc().update("update sales.client_account_membership set status='ACTIVE', "
                        + "updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? "
                        + "and workspace_membership_id=? and client_account_id=?",
                tenantId, workspaceId, membershipId, clientAccountId);
    }

    @Test
    void buyerMembershipDeactivationWinsBeforeWaitingSetupCallback() throws Exception {
        assertRevocationWinsBeforeWaitingSetupCallback(false);
    }

    @Test
    void accountSuspensionWinsBeforeWaitingSetupCallback() throws Exception {
        assertRevocationWinsBeforeWaitingSetupCallback(true);
    }

    @Test
    void suspendedTenantRejectsBuyerSetupCallback() throws Exception {
        UUID tenantId = tenantUuid();
        String setupIntentId = startBuyerSetupIntent();
        SignedProviderWebhook callback = stripePaymentProvider.confirmLocalSetupIntent(setupIntentId);
        String providerPaymentMethodId = stripePaymentProvider.retrieveSetupIntent(setupIntentId).orElseThrow()
                .paymentMethodId();

        assertThat(jdbc.update("update tenant_management.tenant set status='SUSPENDED',"
                        + "updated_at=current_timestamp,version=version+1 where id=?", tenantId))
                .isEqualTo(1);

        assertThat(performSetupWebhook(callback)).isEqualTo(503);
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.buyer_saved_payment_method "
                        + "where tenant_id=? and workspace_id=? and provider_payment_method_id=?", Integer.class,
                tenantId, workspaceUuid(), providerPaymentMethodId)).isZero();
    }

    @Test
    void disabledBuyerUserRejectsBuyerSetupCallback() throws Exception {
        UUID tenantId = tenantUuid();
        UUID workspaceId = workspaceUuid();
        UUID buyerMembershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        String setupIntentId = startBuyerSetupIntent();
        SignedProviderWebhook callback = stripePaymentProvider.confirmLocalSetupIntent(setupIntentId);
        String providerPaymentMethodId = stripePaymentProvider.retrieveSetupIntent(setupIntentId).orElseThrow()
                .paymentMethodId();
        UUID buyerUserId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership "
                + "where id=? and tenant_id=? and workspace_id=?", UUID.class,
                buyerMembershipId, tenantId, workspaceId);

        assertThat(jdbc.update("update iam.user_account set status='DISABLED',updated_at=current_timestamp,"
                        + "version=version+1 where id=?", buyerUserId))
                .isEqualTo(1);

        assertThat(performSetupWebhook(callback)).isEqualTo(403);
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.buyer_saved_payment_method "
                        + "where tenant_id=? and workspace_id=? and provider_payment_method_id=?", Integer.class,
                tenantId, workspaceId, providerPaymentMethodId)).isZero();
    }

    private void assertRevocationWinsBeforeWaitingSetupCallback(boolean suspendAccount) throws Exception {
        UUID tenantId = tenantUuid();
        UUID workspaceId = workspaceUuid();
        UUID clientAccountId = UUID.fromString(buyerClientAccountId());
        UUID buyerMembershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        String setupIntentId = startBuyerSetupIntent();
        SignedProviderWebhook callback = stripePaymentProvider.confirmLocalSetupIntent(setupIntentId);
        UUID setupAttemptId = tenantJdbc().queryForObject("select id from "
                        + "payments.buyer_payment_method_setup_attempt where tenant_id=? and workspace_id=? "
                        + "and provider_setup_intent_id=?", UUID.class, tenantId, workspaceId, setupIntentId);
        String providerPaymentMethodId = stripePaymentProvider.retrieveSetupIntent(setupIntentId).orElseThrow()
                .paymentMethodId();
        String lockKey = buyerPaymentSetupAuthorizationLockKey(tenantId, workspaceId, clientAccountId);
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult account = mockMvc.perform(get("/api/v1/client-accounts/{id}", clientAccountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        String accountEtag = account.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(accountEtag).isNotBlank();

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch accountRowLocked = new CountDownLatch(1);
        CountDownLatch releaseAccountRow = new CountDownLatch(1);
        CompletableFuture<Void> accountRowLockHolder = null;
        CompletableFuture<Integer> revocation = null;
        CompletableFuture<Integer> webhook = null;
        try {
            JdbcTemplate testJdbc = tenantJdbc();
            accountRowLockHolder = CompletableFuture.runAsync(() -> new TransactionTemplate(
                    new DataSourceTransactionManager(testJdbc.getDataSource())).executeWithoutResult(transaction -> {
                testJdbc.queryForObject("select id from sales.client_account where tenant_id=? and workspace_id=? "
                                + "and id=? for update", UUID.class, tenantId, workspaceId, clientAccountId);
                accountRowLocked.countDown();
                try {
                    if (!releaseAccountRow.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out while holding the Client Account row lock");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the Client Account row lock", interrupted);
                }
            }), executor);
            assertThat(accountRowLocked.await(5, TimeUnit.SECONDS)).as("test transaction holds account row").isTrue();

            revocation = CompletableFuture.supplyAsync(() -> performRevocation(
                    suspendAccount, clientAccountId, buyerMembershipId, accountEtag, ownerToken), executor);
            assertThat(awaitAdvisoryLock(lockKey, true, revocation))
                    .as("revocation owns the setup authorization lock before its update")
                    .isTrue();

            webhook = CompletableFuture.supplyAsync(() -> performSetupWebhook(callback), executor);
            assertThat(awaitAdvisoryLock(lockKey, false, webhook))
                    .as("callback waits for revocation's setup authorization lock")
                    .isTrue();

            releaseAccountRow.countDown();
            assertThat(revocation.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(webhook.get(10, TimeUnit.SECONDS)).isEqualTo(403);
            accountRowLockHolder.get(10, TimeUnit.SECONDS);
        } finally {
            releaseAccountRow.countDown();
            if (webhook != null) webhook.cancel(true);
            if (revocation != null) revocation.cancel(true);
            if (accountRowLockHolder != null) accountRowLockHolder.cancel(true);
            executor.shutdownNow();
        }

        assertThat(tenantJdbc().queryForObject("select count(*) from payments.buyer_saved_payment_method "
                        + "where tenant_id=? and workspace_id=? and provider_payment_method_id=?", Integer.class,
                tenantId, workspaceId, providerPaymentMethodId)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.buyer_payment_method_setup_event "
                        + "where tenant_id=? and workspace_id=? and setup_attempt_id=?", Integer.class,
                tenantId, workspaceId, setupAttemptId)).isZero();
    }

    private int performRevocation(boolean suspendAccount, UUID clientAccountId, UUID buyerMembershipId,
            String accountEtag, String salesToken) {
        try {
            MockHttpServletRequestBuilder request = suspendAccount
                    ? post("/api/v1/client-accounts/{id}/suspensions", clientAccountId)
                    : delete("/api/v1/client-accounts/{id}/buyer-memberships/{membershipId}",
                            clientAccountId, buyerMembershipId);
            return mockMvc.perform(request
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                            .header(HttpHeaders.IF_MATCH, accountEtag))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new IllegalStateException("Client Account revocation request failed", exception);
        }
    }

    private int performSetupWebhook(SignedProviderWebhook callback) {
        try {
            return mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Stripe-Signature", callback.signature())
                            .content(callback.payload()))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new IllegalStateException("SetupIntent callback request failed", exception);
        }
    }

    private boolean awaitAdvisoryLock(String lockKey, boolean granted, CompletableFuture<Integer> request)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (request.isDone()) return false;
            try (Connection probe = tenantJdbc().getDataSource().getConnection();
                    PreparedStatement statement = probe.prepareStatement("select exists (select 1 from pg_locks "
                            + "where locktype='advisory' and objsubid=1 and classid::bigint = "
                            + "((hashtextextended(?, 0) >> 32) & 4294967295) and objid::bigint = "
                            + "(hashtextextended(?, 0) & 4294967295) and granted=?)")) {
                statement.setString(1, lockKey);
                statement.setString(2, lockKey);
                statement.setBoolean(3, granted);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return true;
                }
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private String startBuyerSetupIntent() throws Exception {
        MvcResult setup = mockMvc.perform(post("/api/v1/buyer/payment-methods/setup-intents")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken(BUYER_EMAIL, "PORTAL"))
                        .header("Idempotency-Key", "buyer-setup-revocation-race-" + UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn();
        return json(setup).get("setupIntentId").asText();
    }

    private static String buyerPaymentSetupAuthorizationLockKey(UUID tenantId, UUID workspaceId,
            UUID clientAccountId) {
        return tenantId + "|" + workspaceId + "|" + BUYER_SETUP_AUTH_LOCK + "|" + clientAccountId;
    }
}
