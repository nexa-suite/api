package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

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
    void concurrentOperatorActivationReturnsOneStableOutcomeWithoutDuplicates() throws Exception {
        String slug = "it-con-" + uuid().substring(0, 8);
        var submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(OrganizationRegistrationIT.payload(slug, "it-con-" + uuid() + "@example.test"))).andExpect(status().isOk()).andReturn();
        String id = json(submitted).get("registrationId").asText();
        Callable<MvcResult> activate = () -> mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andReturn();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(java.util.List.of(activate, activate)).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(results).allMatch(result -> result.getResponse().getStatus() == 200);
            assertThat(results.get(0).getResponse().getContentAsString())
                    .isEqualTo(results.get(1).getResponse().getContentAsString());
            String tenantId = json(results.get(0)).get("tenantId").asText();
            String workspaceId = json(results.get(0)).get("workspaceId").asText();
            assertThat(jdbc.queryForObject("select count(*) from tenant_management.tenant where id=?", Integer.class,
                    java.util.UUID.fromString(tenantId))).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace where id=?", Integer.class,
                    java.util.UUID.fromString(workspaceId))).isEqualTo(1);
        }
    }
}
