package com.nexa.api.tenantaccessgovernance.iam.application.exception;

/** Stable error codes for token-scoped onboarding draft operations. */
public final class OrganizationRegistrationDraftException extends RuntimeException {
    private final String code;

    public OrganizationRegistrationDraftException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
