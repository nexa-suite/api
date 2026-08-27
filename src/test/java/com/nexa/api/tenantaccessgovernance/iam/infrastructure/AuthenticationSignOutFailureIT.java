package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignOutCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignOutUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves that sign-out failures never become a successful logout response. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthenticationSignOutFailureIT extends PostgresIntegrationSupport {

    @MockitoBean
    SignOutUseCase signOutUseCase;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void browserPersistenceFailureReturnsInternalProblemAndKeepsRefreshCookie() throws Exception {
        doThrow(new DataAccessResourceFailureException("injected sign-out persistence failure"))
                .when(signOutUseCase).signOut(any(SignOutCommand.class));
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        double before = meterRegistry.counter("nexa.security.authentication.signout.failure").count();

        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new Cookie("NEXA_PLATFORM_REFRESH", "still-live-refresh-token")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(meterRegistry.counter("nexa.security.authentication.signout.failure").count())
                .isEqualTo(before + 1);
        verify(signOutUseCase).signOut(any(SignOutCommand.class));
    }

    @Test
    void nativeUnexpectedFailureReturnsInternalProblemWithoutCookieSemantics() throws Exception {
        doThrow(new IllegalStateException("injected sign-out application failure"))
                .when(signOutUseCase).signOut(any(SignOutCommand.class));
        String token = accessToken(SALES_EMAIL, "PLATFORM");

        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Nexa-Client", "NATIVE"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(signOutUseCase).signOut(any(SignOutCommand.class));
    }
}
