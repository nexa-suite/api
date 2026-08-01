package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class AuthenticationThrottleConcurrencyIT extends PostgresIntegrationSupport {
    @Test void concurrentFailuresUpdateOneAtomicThrottleRow() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                try {
                    return mockMvc.perform(post("/api/v1/authentication/sign-in").header("Origin", ALLOWED_ORIGIN).header("User-Agent", "throttle-it")
                                    .contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"throttle@icisa-test.local\",\"password\":\"wrong\",\"workspaceSlug\":\"icisa-test\",\"surface\":\"PLATFORM\"}"))
                            .andReturn().getResponse().getStatus();
                } catch (Exception exception) { throw new AssertionError(exception); }
            })).toList();
            for (var task : tasks) assertThat(task.get()).isIn(401, 429);
        }
        assertThat(jdbc.queryForObject("select count(*) from iam.authentication_failure where normalized_identifier=?", Integer.class, "throttle@icisa-test.local")).isEqualTo(1);
    }
}
