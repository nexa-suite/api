package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PublicContactRequestIT extends PostgresIntegrationSupport {
    private static final String REQUEST = """
            {
              "requestType": "DEMO",
              "name": "Elena Rios",
              "email": "elena@example.com",
              "companyName": "Cold Chain",
              "message": "We need a demo for our refrigerated distribution operation."
            }
            """;

    @BeforeEach
    void resetPublicContactState() {
        jdbc.update("delete from iam.public_contact_request");
        jdbc.update("delete from iam.public_contact_throttle_bucket");
    }

    @Test
    void acceptsAndPersistsWebsiteRequestWithoutProvisioningTenantOrIdentity() throws Exception {
        int tenantsBefore = jdbc.queryForObject("select count(*) from tenant_management.tenant", Integer.class);
        int usersBefore = jdbc.queryForObject("select count(*) from iam.user_account", Integer.class);

        mockMvc.perform(request())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestType").value("DEMO"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(jdbc.queryForObject("select count(*) from iam.public_contact_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.tenant", Integer.class)).isEqualTo(tenantsBefore);
        assertThat(jdbc.queryForObject("select count(*) from iam.user_account", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void rejectsInvalidPayloadBeforePersistence() throws Exception {
        mockMvc.perform(post("/api/v1/public/contact-requests")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"CONTACT\",\"name\":\"E\",\"email\":\"bad\",\"message\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(jdbc.queryForObject("select count(*) from iam.public_contact_request", Integer.class)).isZero();
    }

    @Test
    void appliesDurableEmailAndAddressThrottle() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(request()).andExpect(status().isAccepted());
        }

        mockMvc.perform(request())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PUBLIC_CONTACT_RATE_LIMITED"));

        assertThat(jdbc.queryForObject("select count(*) from iam.public_contact_request", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from iam.public_contact_throttle_bucket", Integer.class)).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return post("/api/v1/public/contact-requests")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST)
                .with(request -> {
                    request.setRemoteAddr("198.51.100.10");
                    return request;
                });
    }
}
