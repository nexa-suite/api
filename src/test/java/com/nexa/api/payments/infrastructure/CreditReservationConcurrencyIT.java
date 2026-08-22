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
        CreditAccountState originalAccount = jdbc.query("select credit_limit,credit_exposure,reserved_exposure,status from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                        (rs, n) -> new CreditAccountState(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4)),
                        tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency())
                .stream().findFirst().orElseThrow();
        BigDecimal limit = firstReceivable.amount().max(secondReceivable.amount());
        jdbc.update("update payments.credit_account set credit_limit=?,credit_exposure=0,reserved_exposure=0 where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                limit, tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> creditPayment(buyer, firstReceivable.id(), "credit-" + uuid()));
            Future<MvcResult> second = executor.submit(() -> creditPayment(buyer, secondReceivable.id(), "credit-" + uuid()));
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 400).count()).isEqualTo(1);
            BigDecimal exposure = jdbc.queryForObject("select credit_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?", BigDecimal.class,
                    tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
            assertThat(exposure).isLessThanOrEqualTo(limit);
        } finally {
            executor.shutdownNow();
            jdbc.update("update payments.receivable set status='VOID',updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id in (?,?)",
                    tenantUuid(), workspaceUuid(), firstReceivable.id(), secondReceivable.id());
            jdbc.update("update payments.credit_account set credit_limit=?,credit_exposure=?,reserved_exposure=?,status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                    originalAccount.limit(), originalAccount.exposure(), originalAccount.reserved(), originalAccount.status(),
                    tenantUuid(), workspaceUuid(), java.util.UUID.fromString(buyerClientAccountId()), firstReceivable.currency());
        }
    }

    private MvcResult creditPayment(String buyer, java.util.UUID receivableId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/receivables/" + receivableId + "/credit-line-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", key)).andReturn();
    }

    private record CreditAccountState(BigDecimal limit, BigDecimal exposure, BigDecimal reserved, String status) { }
}
