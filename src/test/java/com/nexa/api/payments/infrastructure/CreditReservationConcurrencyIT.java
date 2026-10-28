package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CreditReservationConcurrencyIT extends PaymentIntegrationSupport {
    @Test
    void simultaneousCreditPaymentsCannotExceedAvailableLimit() throws Exception {
        OpenReceivable firstReceivable = createOpenReceivable();
        OpenReceivable secondReceivable = createOpenReceivable();
        ensureBuyerCreditAccount(firstReceivable.currency());
        CreditAccountState originalAccount = tenantJdbc().query("select credit_limit,credit_exposure,reserved_exposure,status from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                        (rs, n) -> new CreditAccountState(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4)),
                        tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency())
                .stream().findFirst().orElseThrow();
        BigDecimal limit = firstReceivable.amount().max(secondReceivable.amount());
        tenantJdbc().update("update payments.credit_account set credit_limit=?,credit_exposure=0,reserved_exposure=0 where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                limit, tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> creditPayment(buyer, firstReceivable.id(), "credit-" + uuid()));
            Future<MvcResult> second = executor.submit(() -> creditPayment(buyer, secondReceivable.id(), "credit-" + uuid()));
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 400).count()).isEqualTo(1);
            BigDecimal exposure = tenantJdbc().queryForObject("select credit_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?", BigDecimal.class,
                    tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
            assertThat(exposure).isLessThanOrEqualTo(limit);
        } finally {
            executor.shutdownNow();
            tenantJdbc().update("update payments.receivable set status='VOID',updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id in (?,?)",
                    tenantUuid(), workspaceUuid(), firstReceivable.id(), secondReceivable.id());
            tenantJdbc().update("update payments.credit_account set credit_limit=?,credit_exposure=?,reserved_exposure=?,status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                    originalAccount.limit(), originalAccount.exposure(), originalAccount.reserved(), originalAccount.status(),
                    tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
        }
    }

    @Test
    void creditLinePaymentRejectsNonCreditTermsAndAllowsCreditTerms() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        java.util.UUID accountId = java.util.UUID.fromString(buyerClientAccountId());
        String originalTerms = tenantJdbc().queryForObject(
                "select payment_condition from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                String.class, tenantUuid(), workspaceUuid(), accountId);
        ensureBuyerCreditAccount(receivable.currency());
        tenantJdbc().update("update payments.credit_account set status='ACTIVE',credit_limit=greatest(credit_limit,"
                        + "credit_exposure+reserved_exposure+?),version=version+1,updated_at=current_timestamp "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                receivable.amount().add(new BigDecimal("100.00")), tenantUuid(), workspaceUuid(), accountId,
                receivable.currency());
        BigDecimal exposureBefore = tenantJdbc().queryForObject(
                "select credit_exposure from payments.credit_account where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=?", BigDecimal.class,
                tenantUuid(), workspaceUuid(), accountId, receivable.currency());
        String cashKey = "cash-terms-credit-" + uuid();
        String creditKey = "credit-terms-credit-" + uuid();

        try {
            tenantJdbc().update("update sales.client_account set payment_condition='CASH',version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    tenantUuid(), workspaceUuid(), accountId);
            MvcResult denied = creditPayment(buyer, receivable.id(), cashKey);
            assertThat(denied.getResponse().getStatus()).isEqualTo(400);
            assertThat(denied.getResponse().getContentAsString()).contains("INSUFFICIENT_CREDIT");
            assertThat(tenantJdbc().queryForObject("select count(*) from payments.payment where tenant_id=? "
                            + "and workspace_id=? and created_by_membership_id=? and idempotency_key=?",
                    Integer.class, tenantUuid(), workspaceUuid(), java.util.UUID.fromString(membershipId(BUYER_EMAIL)),
                    cashKey)).isZero();

            for (String rejectedCondition : List.of("NO_CREDIT", "NOT_NET_30", "CASH_CREDIT", "credit_limit",
                    "CREDIT_30_EXTRA")) {
                tenantJdbc().update("update sales.client_account set payment_condition=?,version=version+1 "
                                + "where tenant_id=? and workspace_id=? and id=?",
                        rejectedCondition, tenantUuid(), workspaceUuid(), accountId);
                String rejectedKey = "non-credit-terms-" + uuid();
                MvcResult rejected = creditPayment(buyer, receivable.id(), rejectedKey);
                assertThat(rejected.getResponse().getStatus()).as(rejectedCondition).isEqualTo(400);
                assertThat(rejected.getResponse().getContentAsString()).as(rejectedCondition)
                        .contains("INSUFFICIENT_CREDIT");
                assertThat(tenantJdbc().queryForObject("select count(*) from payments.payment where tenant_id=? "
                                + "and workspace_id=? and created_by_membership_id=? and idempotency_key=?",
                        Integer.class, tenantUuid(), workspaceUuid(),
                        java.util.UUID.fromString(membershipId(BUYER_EMAIL)), rejectedKey))
                        .as(rejectedCondition)
                        .isZero();
                assertThat(tenantJdbc().queryForObject("select credit_exposure from payments.credit_account "
                                + "where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                        BigDecimal.class, tenantUuid(), workspaceUuid(), accountId, receivable.currency()))
                        .as(rejectedCondition)
                        .isEqualByComparingTo(exposureBefore);
            }

            tenantJdbc().update("update sales.client_account set payment_condition='CREDIT_30',version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    tenantUuid(), workspaceUuid(), accountId);
            MvcResult accepted = creditPayment(buyer, receivable.id(), creditKey);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
            assertThat(tenantJdbc().queryForObject("select credit_exposure from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                    BigDecimal.class, tenantUuid(), workspaceUuid(), accountId, receivable.currency()))
                    .isEqualByComparingTo(exposureBefore.add(receivable.amount()));
        } finally {
            tenantJdbc().update("update sales.client_account set payment_condition=?,version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    originalTerms, tenantUuid(), workspaceUuid(), accountId);
        }
    }

    private MvcResult creditPayment(String buyer, java.util.UUID receivableId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/receivables/" + receivableId + "/credit-line-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", key)).andReturn();
    }

    private void ensureBuyerCreditAccount(String currency) {
        tenantJdbc().update("insert into payments.credit_account "
                        + "(id,tenant_id,workspace_id,client_account_id,currency,credit_limit,credit_exposure,reserved_exposure,status,version,created_at,updated_at) "
                        + "select md5(c.id::text || ':' || ?)::uuid,c.tenant_id,c.workspace_id,c.id,?,c.credit_limit,0,0,"
                        + "case when c.status='ACTIVE' then 'ACTIVE' else 'SUSPENDED' end,0,current_timestamp,current_timestamp "
                        + "from sales.client_account c "
                        + "where c.tenant_id=? and c.workspace_id=? and c.id=? "
                        + "on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                currency, currency, tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()));
    }

    private record CreditAccountState(BigDecimal limit, BigDecimal exposure, BigDecimal reserved, String status) { }
}
