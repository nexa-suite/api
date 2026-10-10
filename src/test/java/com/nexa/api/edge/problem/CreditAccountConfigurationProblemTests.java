package com.nexa.api.edge.problem;

import com.nexa.api.creditreceivables.application.exception.CreditAccountConfigurationUnavailableException;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAccountConfigurationProblemTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsCreditConfigurationPreconditionsAndBusinessConflicts() {
        assertProblem("PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED);
        assertProblem("PRECONDITION_FAILED", HttpStatus.PRECONDITION_FAILED, ApiErrorCode.PRECONDITION_FAILED);
        assertProblem("CREDIT_LIMIT_BELOW_USED", HttpStatus.CONFLICT, ApiErrorCode.CREDIT_LIMIT_BELOW_USED);
        assertProblem("CREDIT_ACCOUNT_CLOSED", HttpStatus.CONFLICT, ApiErrorCode.CREDIT_ACCOUNT_CLOSED);
        assertProblem("IDEMPOTENCY_PAYLOAD_CONFLICT", HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT);
        assertProblem("DATA_INTEGRITY_CONFLICT", HttpStatus.CONFLICT, ApiErrorCode.DATA_INTEGRITY_CONFLICT);
    }

    @Test
    void mapsScopedNotFoundAndUnknownCodesWithoutExposingExceptionText() {
        assertProblem("CLIENT_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, ApiErrorCode.CLIENT_ACCOUNT_NOT_FOUND);
        assertProblem("CREDIT_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, ApiErrorCode.CREDIT_ACCOUNT_NOT_FOUND);

        MockHttpServletRequest request = request();
        var unknown = handler.handleCreditReceivable(new CreditReceivableOperationException("UNKNOWN_PRIVATE_DETAIL"), request);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().getProperties()).containsEntry("code", ApiErrorCode.INVALID_REQUEST.name());
        assertThat(unknown.getBody().getDetail()).doesNotContain("UNKNOWN_PRIVATE_DETAIL");
    }

    @Test
    void mapsMissingIdempotencyAndUnavailableTenantStore() {
        assertProblem("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED);

        MockHttpServletRequest request = request();
        var unavailable = handler.handleCreditAccountConfigurationUnavailable(
                new CreditAccountConfigurationUnavailableException(new IllegalStateException("private detail")), request);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody().getProperties())
                .containsEntry("code", ApiErrorCode.TECHNICAL_CAPABILITY_UNAVAILABLE.name());
        assertThat(unavailable.getBody().getDetail()).doesNotContain("private detail");
    }

    private void assertProblem(String code, HttpStatus status, ApiErrorCode responseCode) {
        MockHttpServletRequest request = request();
        var response = handler.handleCreditReceivable(new CreditReceivableOperationException(code), request);
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().getProperties()).containsEntry("code", responseCode.name());
        assertThat(response.getBody().getDetail()).doesNotContain(code);
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("PUT", "/api/v1/credit-account-configurations/customer-accounts/00000000-0000-0000-0000-000000000001");
    }
}
