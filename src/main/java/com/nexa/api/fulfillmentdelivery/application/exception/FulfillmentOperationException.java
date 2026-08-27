package com.nexa.api.fulfillmentdelivery.application.exception;

/**
 * Stable application error for the BC-06 command boundary.
 *
 * <p>The code is intentionally transport-neutral; the shared HTTP handler
 * maps it to Nexa's established ProblemDetail contract.</p>
 */
public final class FulfillmentOperationException extends RuntimeException {
    private final String code;
    private final boolean notFound;

    public FulfillmentOperationException(String code, boolean notFound) {
        super(code);
        this.code = code;
        this.notFound = notFound;
    }

    public String code() { return code; }

    public boolean notFound() { return notFound; }
}
