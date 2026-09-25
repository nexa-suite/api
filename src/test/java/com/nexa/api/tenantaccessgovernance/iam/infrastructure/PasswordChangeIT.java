package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PasswordChangeIT extends PostgresIntegrationSupport {
    private static final String RAW_CONTEXT_TICKET = "pending-change-context-ticket";

    @AfterEach
    void restoreSeedPassword() {
        jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=current_timestamp where user_id=(select id from iam.user_account where normalized_email=?)",
                new BCryptPasswordEncoder(12).encode(TEST_PASSWORD), OWNER_EMAIL);
        jdbc.update("delete from iam.access_context_selection_ticket where ticket_hash=?", sha256(RAW_CONTEXT_TICKET));
    }

    @Test
    void changesPasswordAndRejectsWrongCurrentPasswordThroughHttp() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        String ticketHash = sha256(RAW_CONTEXT_TICKET);
        java.util.UUID userId = jdbc.queryForObject("select id from iam.user_account where normalized_email=?", java.util.UUID.class, OWNER_EMAIL);
        jdbc.update("insert into iam.access_context_selection_ticket (ticket_hash,user_id,surface,issued_at,expires_at,consumed_at) "
                        + "values (?,?, 'PLATFORM', current_timestamp, current_timestamp + interval '5 minutes', null)",
                ticketHash, userId);
        mockMvc.perform(post("/api/v1/me/password-changes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"integration-new-password-2026\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select revoked_at from iam.access_context_selection_ticket where ticket_hash=?",
                java.sql.Timestamp.class, ticketHash)).isNotNull();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/access-contexts")
                        .header("X-Nexa-Client", "NATIVE")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header("X-Nexa-Context-Ticket", RAW_CONTEXT_TICKET))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/password-changes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"integration-new-password-2026\"}"))
                .andExpect(status().isBadRequest());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
