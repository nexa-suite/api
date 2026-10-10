package com.nexa.api.creditreceivables.application.exception;

/** Tenant credit configuration storage or routing is unavailable; no central fallback is permitted. */
public final class CreditAccountConfigurationUnavailableException extends RuntimeException {
    public CreditAccountConfigurationUnavailableException(Throwable cause) {
        super("Tenant credit configuration is unavailable", cause);
    }
}
