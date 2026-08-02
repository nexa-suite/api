package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SystemOperatorSecurityIT extends PostgresIntegrationSupport {
    private static final String OPERATOR_TOKEN = "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz";

    @BeforeEach
    void clearOperatorThrottle() { jdbc.update("delete from iam.system_operator_throttle_bucket"); }

    @Test
    void rejectsMissingInvalidBrowserAndTenantCredentialsButAcceptsOnlyTheOperatorCredential() throws Exception {
        String id = uuid();
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "invalid-operator-credential"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Nexa-System-Operator", OPERATOR_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("Origin", "https://evil.example")
                        .header("X-Nexa-System-Operator", OPERATOR_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("Authorization", "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("Authorization", "Bearer " + accessToken(BUYER_EMAIL, "PORTAL")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", OPERATOR_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentInvalidBurstIsBoundedByPersistentOperatorThrottle() throws Exception {
        String remoteAddress = "operator-burst-" + uuid();
        String correlation = "operator-burst-" + uuid();
        int requestCount = 24;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            List<java.util.concurrent.Future<Integer>> responses = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(post("/api/v1/internal/organization-registrations/" + uuid() + "/activation")
                                        .header("X-Correlation-ID", correlation)
                                        .header("X-Nexa-System-Operator", "invalid-operator-credential")
                                        .with(request -> {
                                            request.setRemoteAddr(remoteAddress);
                                            return request;
                                        }))
                                .andReturn().getResponse().getStatus();
                    })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var response : responses) assertThat(response.get()).isEqualTo(403);
        }

        String bucketHash = sha256(remoteAddress);
        assertThat(jdbc.queryForObject("select failure_count from iam.system_operator_throttle_bucket where bucket_key_hash=?",
                Integer.class, bucketHash)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from iam.security_audit_event where event_type='SYSTEM_OPERATOR_AUTHENTICATION_FAILED' and correlation_id=?",
                Long.class, correlation)).isEqualTo(10L);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
