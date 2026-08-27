package com.nexa.api.creditreceivables.application.exception;

/** Stable transport-neutral error from the BC-07 command boundary. */
public final class CreditReceivableOperationException extends RuntimeException {
    private final String code;
    private final boolean notFound;

    public CreditReceivableOperationException(String code) {
        this(code, code != null && code.endsWith("_NOT_FOUND"));
    }

    public CreditReceivableOperationException(String code, boolean notFound) {
        super(code);
        this.code = code;
        this.notFound = notFound;
    }

    public String code() { return code; }

    public boolean notFound() { return notFound; }
}
