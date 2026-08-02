package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PasswordResetThrottleConcurrencyIT extends PostgresIntegrationSupport {
    @BeforeEach
    void clearResetThrottle() { jdbc.update("delete from iam.password_reset_throttle_bucket"); }

    @Test
    void atomicEmailThrottleAllowsThreeRequestsAndBlocksTheFourth() throws Exception {
        String email = "throttle-" + uuid() + "@example.test";
        Callable<Integer> attempt = () -> request(email);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var statuses = executor.invokeAll(java.util.List.of(attempt, attempt, attempt, attempt)).stream()
                    .map(future -> {
                        try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
                    }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 200, 200, 429);
        }
    }

    private int request(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset-requests").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"surface\":\"PORTAL\"}"))
                .andReturn().getResponse().getStatus();
    }
}
