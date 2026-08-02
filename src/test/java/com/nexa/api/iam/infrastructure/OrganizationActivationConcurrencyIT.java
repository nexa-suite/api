package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OrganizationActivationConcurrencyIT extends NexaWorkflowIntegrationSupport {
    @Test
    void concurrentOperatorActivationHasOneWinningLifecycleTransition() throws Exception {
        String slug = "it-con-" + uuid().substring(0, 8);
        var submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(OrganizationRegistrationIT.payload(slug, "it-con-" + uuid() + "@example.test"))).andExpect(status().isOk()).andReturn();
        String id = json(submitted).get("registrationId").asText();
        Callable<Integer> activate = () -> mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andReturn().getResponse().getStatus();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(java.util.List.of(activate, activate)).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(results.stream().filter(status -> status == 200).count()).isEqualTo(1);
            assertThat(results.stream().filter(status -> status != 200).count()).isEqualTo(1);
        }
    }
}
