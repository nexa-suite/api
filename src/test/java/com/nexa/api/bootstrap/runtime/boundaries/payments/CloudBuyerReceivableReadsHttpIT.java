package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.boundaries.TenantPaymentServiceBindingsFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.tenant-business.payments.enabled=false")
@Import(CloudBuyerReceivableReadsHttpIT.ReadComposition.class)
class CloudBuyerReceivableReadsHttpIT extends PostgresIntegrationSupport {
    private static final String RECEIVABLES = "/api/v1/receivables";

    @Test
    void buyerOnlyReadsReceivablesAndPaymentHistoryForTheirActiveAccount() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.fromString(buyerClientAccountId());
        UUID other = UUID.randomUUID();
        String suffix = other.toString().substring(0, 8);
        String taxNumber = "20" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(other.getMostSignificantBits(), 1_000_000_000L));
        tenantJdbc().update("""
                insert into sales.client_account
                    (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,
                     tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,
                     status,created_at,updated_at)
                values (?,?,?,?,'Other client','Other client','PE','RUC',?,'RETAIL','Test contact',?,
                        '+51000000000','STANDARD','IMMEDIATE','ACTIVE',current_timestamp,current_timestamp)
                """, other, tenant, workspace, "AR-" + suffix, taxNumber, "receivable-" + suffix + "@example.test");
        UUID own = seedReceivable(tenant, workspace, account);
        UUID foreign = seedReceivable(tenant, workspace, other);
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String owner = "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM");

        mockMvc.perform(get(RECEIVABLES).param("clientAccountId", other.toString()).header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(own.toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(foreign.toString()))));
        mockMvc.perform(get(RECEIVABLES + "/{id}", own).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(own.toString()));
        mockMvc.perform(get(RECEIVABLES + "/{id}/payments", own).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(RECEIVABLES + "/{id}", foreign).header("Authorization", buyer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RECEIVABLES + "/{id}/payments", foreign).header("Authorization", buyer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RECEIVABLES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RECEIVABLES).header("Authorization", owner))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post(RECEIVABLES + "/{id}/bank-transfer-payments", own).header("Authorization", buyer)
                        .header("Idempotency-Key", "unchanged-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"SYNTHETIC\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    private UUID seedReceivable(UUID tenant, UUID workspace, UUID account) {
        UUID id = UUID.randomUUID();
        tenantJdbc().update("""
                insert into payments.receivable
                    (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,receivable_number,
                     currency,amount,due_at,status,created_at,updated_at)
                values (?,?,?,?,'SALES_ORDER',?,?,'PEN',10,current_timestamp,'OPEN',current_timestamp,current_timestamp)
                """, id, tenant, workspace, account, UUID.randomUUID(), "AR-" + id.toString().substring(0, 8));
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadComposition {
        @Bean
        TenantPaymentUnavailableAdapter.BuyerReceivableRoutes cloudBuyerReceivablesForHttpTest(
                TenantBusinessDatabaseRouter router, TenantPaymentServiceBindingsFactory services) {
            return new TenantPaymentUnavailableConfiguration().cloudBuyerReceivableRoutes(router, services);
        }
    }
}
