package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SystemOperatorSecurityIT extends PostgresIntegrationSupport {
    @BeforeEach
    void clearOperatorThrottle() { jdbc.update("delete from iam.system_operator_throttle_bucket"); }

    @Test
    void internalActivationRequiresDedicatedOperatorHeader() throws Exception {
        String id = uuid();
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andExpect(status().isNotFound());
    }
}
