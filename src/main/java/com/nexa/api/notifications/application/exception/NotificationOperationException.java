package com.nexa.api.notifications.application.exception;

/** Stable application error for the BC-10 native push contract. */
public final class NotificationOperationException extends RuntimeException {
    private final String code;
    private final boolean notFound;

    public NotificationOperationException(String code, boolean notFound) {
        super(code);
        this.code = code;
        this.notFound = notFound;
    }

    public String code() { return code; }
    public boolean notFound() { return notFound; }
}
