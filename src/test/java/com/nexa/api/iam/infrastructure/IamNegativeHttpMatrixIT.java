package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class IamNegativeHttpMatrixIT extends NexaWorkflowIntegrationSupport {
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    @BeforeEach
    void restoreOwnerFixturesBeforeEach() {
        restoreOwnerFixtures();
    }

    @AfterEach
    void restoreOwnerFixturesAfterEach() {
        restoreOwnerFixtures();
    }

    @Test
    void profileRequiresIfMatchAndRejectsAStaleValidVersion() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        String originalEtag = mockMvc.perform(get("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner", "+51987654321", "es", "America/Lima")))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", originalEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner", "+51987654321", "es", "America/Lima")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", originalEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner Again", "+51987654321", "es", "America/Lima")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
    }

    @Test
    void rejectsInvalidTimezoneLanguageAndPhoneThroughTheProfileHttpBoundary() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        String etag = profileEtag(token);

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner", "+51987654321", "es", "Mars/NotAZone")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_INVALID"));

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner", "+51987654321", "fr", "America/Lima")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profilePayload("Negative Matrix Owner", "invalid", "es", "America/Lima")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_INVALID"));
    }

    @Test
    void rejectsPasswordReuseAndCommonPasswordsThroughHttp() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");

        mockMvc.perform(post("/api/v1/me/password-changes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordPayload(TEST_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REUSE_NOT_ALLOWED"));

        mockMvc.perform(post("/api/v1/me/password-changes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordPayload("123456789012")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_INVALID"));
    }

    @Test
    void hidesForeignSessionAsNotFound() throws Exception {
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        String foreignToken = accessToken(SALES_EMAIL, "PLATFORM");
        var foreignSessions = mockMvc.perform(get("/api/v1/me/sessions")
                        .header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isOk()).andReturn();
        String foreignSessionId = json(foreignSessions).get("sessions").get(0).get("sessionId").asText();

        mockMvc.perform(delete("/api/v1/me/sessions/" + foreignSessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void revokesTheCurrentSessionAndInvalidatesItsAccessToken() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        var listed = mockMvc.perform(get("/api/v1/me/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String currentSessionId = null;
        for (var session : json(listed).get("sessions")) {
            if (session.get("current").asBoolean()) {
                currentSessionId = session.get("sessionId").asText();
                break;
            }
        }
        assertThat(currentSessionId).as("current session id").isNotBlank();

        mockMvc.perform(delete("/api/v1/me/sessions/" + currentSessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    void mapsSequentialDuplicateOrganizationSlugToItsSpecificProblemCode() throws Exception {
        String slug = "it-neg-dup-" + uuid().substring(0, 8);
        MvcResult first = submitRegistration(slug, "it-neg-first-" + uuid() + "@example.test");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult duplicate = submitRegistration(slug, "it-neg-second-" + uuid() + "@example.test");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(duplicate).get("code").asText()).isEqualTo("REGISTRATION_SLUG_CONFLICT");
    }

    @Test
    void concurrentDuplicateOrganizationSlugHasOneWinnerAndSpecificConflict() throws Exception {
        String slug = "it-neg-race-" + uuid().substring(0, 8);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<MvcResult> first = concurrentRegistrationAttempt(barrier, slug, "it-neg-race-a-" + uuid() + "@example.test");
        Callable<MvcResult> second = concurrentRegistrationAttempt(barrier, slug, "it-neg-race-b-" + uuid() + "@example.test");

        List<MvcResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(List.of(first, second)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();
        }

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(json(conflict).get("code").asText()).isEqualTo("REGISTRATION_SLUG_CONFLICT");
    }

    private String profileEtag(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private MvcResult submitRegistration(String slug, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/tenant-management/organization-registrations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrganizationRegistrationIT.payload(slug, email)))
                .andReturn();
    }

    private Callable<MvcResult> concurrentRegistrationAttempt(CyclicBarrier barrier, String slug, String email) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return submitRegistration(slug, email);
        };
    }

    private void restoreOwnerFixtures() {
        jdbc.update("update iam.user_account set display_name='Owner',phone=null,preferred_language='es',timezone='UTC' where normalized_email=?", OWNER_EMAIL);
        jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=current_timestamp where user_id=(select id from iam.user_account where normalized_email=?)",
                PASSWORD_ENCODER.encode(TEST_PASSWORD), OWNER_EMAIL);
    }

    private static String profilePayload(String displayName, String phone, String language, String timezone) {
        return "{\"displayName\":\"" + displayName + "\",\"phone\":\"" + phone + "\",\"preferredLanguage\":\""
                + language + "\",\"timezone\":\"" + timezone + "\"}";
    }

    private static String passwordPayload(String newPassword) {
        return "{\"currentPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"" + newPassword + "\"}";
    }
}
