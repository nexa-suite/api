package com.nexa.api.edge.http.openapi;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SwaggerPresentationIT extends NexaWorkflowIntegrationSupport {
    @Test
    void brandCrudUsesRealTenantStorageAndVersionPreconditions() throws Exception {
        var signedIn = mockMvc.perform(post("/api/v1/authentication/sign-in")
                .header("X-Nexa-Client", "NATIVE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + OWNER_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                        + "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = json(signedIn).path("accessToken").asText();
        assertThat(token).isNotBlank();
        String body = "{\"slug\":\"demo-" + java.util.UUID.randomUUID()
                + "\",\"name\":\"Presentation brand\",\"description\":\"CRUD verification\"}";
        var created = mockMvc.perform(post("/api/v1/catalog/brands")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String path = "/api/v1/catalog/brands/" + json(created).path("id").asText();
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        var updated = mockMvc.perform(patch(path).header("Authorization", "Bearer " + token)
                .header("If-Match", created.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("Presentation brand", "Updated brand")))
                .andExpect(status().isOk()).andReturn();
        var deactivated = mockMvc.perform(post(path + "/deactivations")
                .header("Authorization", "Bearer " + token)
                .header("If-Match", updated.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(deactivated).path("status").asText()).isEqualTo("INACTIVE");
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void presentationIsSmallerAndKeepsCurrentCrudAndSecurityContracts() throws Exception {
        var full = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var demo = mockMvc.perform(get("/v3/api-docs/presentation")).andExpect(status().isOk()).andReturn();
        var document = json(demo);
        assertThat(document.path("servers").get(0).path("url").asText()).isEqualTo("/");
        var paths = document.path("paths");
        assertThat(paths.has("/api/v1/catalog/brands")).isTrue();
        assertThat(paths.path("/api/v1/catalog/brands").has("post")).isTrue();
        assertThat(paths.path("/api/v1/catalog/brands/{id}").has("patch")).isTrue();
        assertThat(paths.path("/api/v1/catalog/categories").has("post")).isTrue();
        assertThat(paths.path("/api/v1/warehouses/{id}/location").has("get")).isTrue();
        assertThat(paths.path("/api/v1/warehouses/{id}/profile").has("patch")).isTrue();
        assertThat(paths.path("/api/v1/warehouses/{id}/locations").has("get")).isFalse();
        assertThat(paths.path("/api/v1/warehouses/{id}/operational-profile").has("patch")).isFalse();
        assertThat(document.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        var references = java.util.regex.Pattern.compile("\"\\$ref\":\"(#[^\"]+)\"")
                .matcher(document.toString());
        int referenceCount = 0;
        while (references.find()) {
            referenceCount++;
            String reference = references.group(1);
            assertThat(document.at(reference.substring(1)).isMissingNode())
                    .as("Presentation reference resolves: %s", reference).isFalse();
        }
        assertThat(referenceCount).isPositive();
        var update = paths.path("/api/v1/catalog/brands/{id}").path("patch");
        assertThat(update.path("responses").has("428")).isTrue();
        assertThat(update.path("parameters").toString()).contains("If-Match");
        assertThat(paths.size()).isLessThan(json(full).path("paths").size());
        assertThat(demo.getResponse().getContentAsByteArray().length)
                .isLessThan(full.getResponse().getContentAsByteArray().length / 2);
        var complete = json(mockMvc.perform(get("/v3/api-docs/complete"))
                .andExpect(status().isOk()).andReturn());
        assertThat(complete.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(complete.path("paths").path("/api/v1/warehouses/{id}/locations").has("get")).isTrue();
        System.out.printf("Swagger payload: complete=%d bytes, presentation=%d bytes, paths=%d/%d%n",
                full.getResponse().getContentAsByteArray().length,
                demo.getResponse().getContentAsByteArray().length, paths.size(), json(full).path("paths").size());
    }
}
