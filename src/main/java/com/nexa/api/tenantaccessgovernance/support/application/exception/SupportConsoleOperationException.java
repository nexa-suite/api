package com.nexa.api.tenantaccessgovernance.support.application.exception;

/** A support-console request was denied or could not complete in its current state. */
public final class SupportConsoleOperationException extends RuntimeException {
	public enum Kind { FORBIDDEN, CONFLICT }

	private final Kind kind;

	public SupportConsoleOperationException(Kind kind, String message) {
		super(message);
		this.kind = java.util.Objects.requireNonNull(kind);
	}

	public Kind kind() { return kind; }
}
