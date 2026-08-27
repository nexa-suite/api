package com.nexa.api.salescommitment.application.exception;

/** Business rejection from the atomic commercial/inventory decision. */
public class CommercialBusinessException extends RuntimeException {
    private final String code;

    public CommercialBusinessException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
