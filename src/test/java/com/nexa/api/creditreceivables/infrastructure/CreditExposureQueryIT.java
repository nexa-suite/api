package com.nexa.api.creditreceivables.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CreditExposureQueryIT extends NexaWorkflowIntegrationSupport {
    @Test
    void salesReadsCurrentScopedExposureWithAuthoritativeComponents() throws Exception {
        UUID accountId = UUID.fromString(buyerClientAccountId());
        UUID tenantId = UUID.fromString(tenantId());
        UUID workspaceId = UUID.fromString(workspaceId());
        CreditAccountState original = creditAccount(accountId, tenantId, workspaceId, "PEN");
        ensureCreditAccount(accountId, tenantId, workspaceId, "PEN");

        try {
            jdbc.update("update payments.credit_account set credit_limit=1000.00,credit_exposure=125.00,reserved_exposure=75.00,status='ACTIVE',version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                    tenantId, workspaceId, accountId);
            BigDecimal receivables = jdbc.queryForObject("select coalesce(sum(amount+coalesce(adjustment_total,0)-amount_paid),0) from payments.receivable where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN' and status in ('OPEN','PARTIALLY_PAID','OVERDUE')",
                    BigDecimal.class, tenantId, workspaceId, accountId);

            String sales = accessToken(SALES_EMAIL, "PLATFORM");
            MvcResult result = mockMvc.perform(get("/api/v1/client-accounts/" + accountId + "/credit-exposure")
                            .param("currency", "pen")
                            .header("Authorization", "Bearer " + sales))
                    .andExpect(status().isOk()).andReturn();

            var body = json(result);
            assertThat(body.get("clientAccountId").asText()).isEqualTo(accountId.toString());
            assertThat(body.get("currency").asText()).isEqualTo("PEN");
            assertThat(body.get("creditLimit").decimalValue()).isEqualByComparingTo("1000.00");
            assertThat(body.get("ledgerExposure").decimalValue()).isEqualByComparingTo("125.00");
            assertThat(body.get("reservedExposure").decimalValue()).isEqualByComparingTo("75.00");
            assertThat(body.get("outstandingReceivables").decimalValue()).isEqualByComparingTo(receivables);
            BigDecimal used = new BigDecimal("200.00").add(receivables);
            assertThat(body.get("used").decimalValue()).isEqualByComparingTo(used);
            assertThat(body.get("availableCredit").decimalValue()).isEqualByComparingTo(new BigDecimal("1000.00").subtract(used).max(BigDecimal.ZERO));
            assertThat(body.get("active").asBoolean()).isTrue();
            assertThat(Instant.parse(body.get("asOf").asText())).isBeforeOrEqualTo(Instant.now());

            String buyer = accessToken(BUYER_EMAIL, "PORTAL");
            mockMvc.perform(get("/api/v1/client-accounts/" + accountId + "/credit-exposure")
                            .header("Authorization", "Bearer " + buyer))
                    .andExpect(status().isForbidden());
        } finally {
            restoreCreditAccount(accountId, tenantId, workspaceId, original);
        }
    }

    @Test
    void customerLookupCannotCrossCurrentWorkspaceScope() throws Exception {
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/client-accounts/" + UUID.randomUUID() + "/credit-exposure")
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
    }

    private CreditAccountState creditAccount(UUID clientAccountId, UUID tenantId, UUID workspaceId, String currency) {
        return jdbc.query("select id,credit_limit,credit_exposure,reserved_exposure,status,version,created_at,updated_at from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?",
                        (rs, ignored) -> new CreditAccountState(rs.getObject("id", UUID.class), rs.getBigDecimal("credit_limit"),
                                rs.getBigDecimal("credit_exposure"), rs.getBigDecimal("reserved_exposure"), rs.getString("status"),
                                rs.getLong("version"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")),
                        tenantId, workspaceId, clientAccountId, currency)
                .stream().findFirst().orElse(null);
    }

    private void ensureCreditAccount(UUID clientAccountId, UUID tenantId, UUID workspaceId, String currency) {
        jdbc.update("insert into payments.credit_account (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,status,version,created_at,updated_at) "
                        + "select md5(c.id::text || ':' || ?)::uuid,c.tenant_id,c.workspace_id,c.id,?,c.credit_limit,'ACTIVE',0,current_timestamp,current_timestamp "
                        + "from sales.client_account c where c.tenant_id=? and c.workspace_id=? and c.id=? "
                        + "on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                currency, currency, tenantId, workspaceId, clientAccountId);
    }

    private void restoreCreditAccount(UUID clientAccountId, UUID tenantId, UUID workspaceId, CreditAccountState original) {
        if (original == null) {
            jdbc.update("delete from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                    tenantId, workspaceId, clientAccountId);
            return;
        }
        jdbc.update("update payments.credit_account set credit_limit=?,credit_exposure=?,reserved_exposure=?,status=?,version=?,created_at=?,updated_at=? where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                original.limit(), original.exposure(), original.reserved(), original.status(), original.version(), original.createdAt(), original.updatedAt(),
                tenantId, workspaceId, clientAccountId);
    }

    private record CreditAccountState(UUID id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved,
                                      String status, long version, Timestamp createdAt, Timestamp updatedAt) { }
}
