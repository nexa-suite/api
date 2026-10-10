package com.nexa.api.creditreceivables.application.exception;

/** Tenant Credit & Receivables storage or routing is unavailable; no central fallback is permitted. */
public final class CreditReceivablesUnavailableException extends RuntimeException {
    public CreditReceivablesUnavailableException(Throwable cause) {
        super("Tenant Credit and Receivables are unavailable", cause);
    }
}
