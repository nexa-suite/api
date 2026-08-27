package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantContextSecurityIT extends PostgresIntegrationSupport {
    @Test void roleAndTenantBoundariesRejectCrossContextCommands() throws Exception {
        mockMvc.perform(get("/api/v1/dispatch-orders")).andExpect(status().isUnauthorized());
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/dispatch-orders").header("Authorization", "Bearer " + owner)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dispatch-orders/" + uuid() + "/preparation-starts").header("Authorization", "Bearer " + sales).header("If-Match", "0").header("Idempotency-Key", "x")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dispatch-orders/" + uuid() + "/preparation-starts").header("Authorization", "Bearer " + warehouse).header("If-Match", "0").header("Idempotency-Key", "x")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/my-deliveries/" + uuid()).header("Authorization", "Bearer " + buyer)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/dispatch-orders/" + uuid() + "/preparation-starts").header("Authorization", "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM")).header("Idempotency-Key", "x")).andExpect(status().isPreconditionRequired());
        mockMvc.perform(get("/api/v1/dispatch-orders?sort=invalid").header("Authorization", "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM")).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"X\",\"name\":\"X\"}" )).andExpect(status().isForbidden());
    }
}
