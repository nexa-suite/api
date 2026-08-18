package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentTenantIsolationIT extends PaymentIntegrationSupport {
    @Test
    void currentTenantCannotReadForeignReceivable() throws Exception {
        ForeignReceivable foreign = createForeignReceivable();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");

        mockMvc.perform(get("/api/v1/receivables/" + foreign.receivableId())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from payments.receivable where tenant_id=? and workspace_id=? and id=?", Integer.class,
                foreign.tenantId(), foreign.workspaceId(), foreign.receivableId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments.receivable where tenant_id=? and workspace_id=? and id=?", Integer.class,
                tenantUuid(), workspaceUuid(), foreign.receivableId())).isZero();
    }
}
