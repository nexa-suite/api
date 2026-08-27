package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PasswordResetIT extends NexaWorkflowIntegrationSupport {
    private static final String RESET_TOKEN = "integration-reset-token-2026";

    @BeforeEach
    void clearResetThrottle() { jdbc.update("delete from iam.password_reset_throttle_bucket"); }

    @AfterEach
    void restoreSeedCredential() {
        jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=current_timestamp where user_id=(select id from iam.user_account where normalized_email=?)",
                new BCryptPasswordEncoder(12).encode(TEST_PASSWORD), BUYER_EMAIL);
        jdbc.update("delete from iam.password_reset_request where normalized_email=?", BUYER_EMAIL);
    }

    @Test
    void returnsGenericUnknownAccountResponseAndConsumesSingleUseResetToken() throws Exception {
        var unknown = mockMvc.perform(post("/api/v1/auth/password-reset-requests").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-" + uuid() + "@example.test\",\"surface\":\"PORTAL\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(unknown).get("message").asText()).doesNotContain("unknown");
        String hash = sha256(RESET_TOKEN);
        jdbc.update("insert into iam.password_reset_request (id,normalized_email,surface,token_hash,status,attempts,expires_at,created_at) values (gen_random_uuid(),?,'PORTAL',?,'PENDING',0,?,?)",
                BUYER_EMAIL, hash, java.sql.Timestamp.from(Instant.now().plusSeconds(1800)), java.sql.Timestamp.from(Instant.now()));
        mockMvc.perform(post("/api/v1/auth/password-resets").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + RESET_TOKEN + "\",\"newPassword\":\"integration-reset-password-2026\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from iam.password_reset_request where token_hash=?", String.class, hash)).isEqualTo("CONSUMED");
        mockMvc.perform(post("/api/v1/auth/password-resets").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + RESET_TOKEN + "\",\"newPassword\":\"integration-reset-password-2026\"}"))
                .andExpect(status().isBadRequest());
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
