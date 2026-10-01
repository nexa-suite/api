package com.nexa.api.businessdocuments.application.exception;

/** Raised when an evidence idempotency key is reused for different immutable request or bytes. */
public final class BusinessEvidenceIdempotencyConflictException extends RuntimeException {
    public BusinessEvidenceIdempotencyConflictException() {
        super("Business evidence idempotency payload conflict");
    }
}
