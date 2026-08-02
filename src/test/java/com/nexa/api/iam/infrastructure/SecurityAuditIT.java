package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SecurityAuditIT extends PostgresIntegrationSupport {
    @BeforeEach
    void clearResetThrottle() { jdbc.update("delete from iam.password_reset_throttle_bucket"); }

    @Test
    void resetRequestAppendsAuditWithoutPersistingRawToken() throws Exception {
        String email = "audit-" + uuid() + "@example.test";
        mockMvc.perform(post("/api/v1/auth/password-reset-requests").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"surface\":\"PORTAL\"}"))
                .andExpect(status().isOk());
        Integer count = jdbc.queryForObject("select count(*) from iam.security_audit_event where event_type='PASSWORD_RESET_REQUESTED' and metadata_json->>'accountResponse'='generic'", Integer.class);
        assertThat(count).isGreaterThan(0);
    }
}
