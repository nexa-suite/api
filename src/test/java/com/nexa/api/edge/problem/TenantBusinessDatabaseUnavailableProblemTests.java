package com.nexa.api.edge.problem;

import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBusinessDatabaseUnavailableProblemTests {

    @Test
    void mapsMissingTenantBindingToGenericServiceUnavailableProblem() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/direct-orders");

        var response = new GlobalExceptionHandler().handleTenantBusinessDatabaseUnavailable(
                new TenantBusinessDatabaseUnavailableException("private binding and database detail"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getProperties())
                .containsEntry("code", ApiErrorCode.TECHNICAL_CAPABILITY_UNAVAILABLE.name());
        assertThat(response.getBody().getDetail()).doesNotContain("private binding", "database detail");
    }
}
