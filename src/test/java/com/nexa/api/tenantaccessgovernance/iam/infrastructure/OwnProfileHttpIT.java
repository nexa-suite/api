package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OwnProfileHttpIT extends PostgresIntegrationSupport {
    @AfterEach
    void restoreProfileFixture() {
        jdbc.update("update iam.user_account set display_name='Owner',phone=null,preferred_language='es',timezone='UTC',"
                + "first_name=null,last_name=null,document_type=null,document_number=null,document_country_code=null where normalized_email=?",
                OWNER_EMAIL);
    }

    @Test
    void authenticatedUserReadsAndOptimisticallyUpdatesOwnProfile() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        var current = mockMvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(OWNER_EMAIL)).andReturn();
        String etag = current.getResponse().getHeader("ETag");
        var updated = mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner\",\"phone\":\"+51987654321\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\","
                                + "\"firstName\":\"Ana\",\"lastName\":\"Prueba\",\"documentType\":\"dni\",\"documentNumber\":\"00000000\",\"documentCountryCode\":\"pe\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("ICISA Owner"))
                .andExpect(jsonPath("$.firstName").value("Ana")).andExpect(jsonPath("$.lastName").value("Prueba"))
                .andExpect(jsonPath("$.documentType").value("DNI")).andExpect(jsonPath("$.documentNumber").value("00000000"))
                .andExpect(jsonPath("$.documentCountryCode").value("PE")).andReturn();
        String updatedEtag = updated.getResponse().getHeader("ETag");

        mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", updatedEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner Legacy\",\"phone\":\"+51987654321\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Ana"))
                .andExpect(jsonPath("$.documentNumber").value("00000000"));

        mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", updatedEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner Invalid\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\",\"documentType\":\"DNI\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFILE_INVALID"));

        mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner Stale\",\"phone\":\"+51987654321\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
    }
}
