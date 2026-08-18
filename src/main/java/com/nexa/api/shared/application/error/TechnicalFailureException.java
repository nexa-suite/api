package com.nexa.api.shared.application.error;

/**
 * Business-independent failure raised at a technical or integration boundary.
 * The message is operational context only and is never rendered to clients.
 */
public final class TechnicalFailureException extends RuntimeException {
    public enum Kind {
        EXTERNAL_TEMPORARY_FAILURE,
        EXTERNAL_TIMEOUT,
        TECHNICAL_CAPABILITY_UNAVAILABLE,
        STORAGE_UNAVAILABLE,
        SCANNER_UNAVAILABLE
    }

    private final Kind kind;
    private final String providerRequestId;

    public TechnicalFailureException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public TechnicalFailureException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    public TechnicalFailureException(Kind kind, String message, Throwable cause, String providerRequestId) {
        super(message, cause);
        this.kind = kind;
        this.providerRequestId = providerRequestId;
    }

    public Kind kind() {
        return kind;
    }

    public String providerRequestId() {
        return providerRequestId;
    }
}
