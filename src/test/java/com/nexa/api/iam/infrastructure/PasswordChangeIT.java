package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PasswordChangeIT extends PostgresIntegrationSupport {
    @AfterEach
    void restoreSeedPassword() {
        jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=current_timestamp where user_id=(select id from iam.user_account where normalized_email=?)",
                new BCryptPasswordEncoder(12).encode(TEST_PASSWORD), OWNER_EMAIL);
    }

    @Test
    void changesPasswordAndRejectsWrongCurrentPasswordThroughHttp() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/me/password-changes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"integration-new-password-2026\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/me/password-changes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"integration-new-password-2026\"}"))
                .andExpect(status().isBadRequest());
    }
}
