package com.nexa.api.tenantaccessgovernance.iam.application.model;

import java.time.Instant;
import java.util.Objects;

public record IdentitySignInResult(Outcome outcome, AuthenticationResult authentication,
		String accessContextTicket, Instant ticketExpiresAt) {
	public enum Outcome { SESSION_ESTABLISHED, CONTEXT_SELECTION_REQUIRED, NO_WORK_CONTEXT }

	public IdentitySignInResult {
		Objects.requireNonNull(outcome, "Identity sign-in outcome is required");
		if (outcome == Outcome.SESSION_ESTABLISHED && (authentication == null || accessContextTicket != null || ticketExpiresAt != null)) {
			throw new IllegalArgumentException("Session-established outcome must contain only an authoritative session");
		}
		if (outcome == Outcome.CONTEXT_SELECTION_REQUIRED
				&& (authentication != null || accessContextTicket == null || accessContextTicket.isBlank() || ticketExpiresAt == null)) {
			throw new IllegalArgumentException("Context selection outcome must contain a pre-context ticket and expiry");
		}
		if (outcome == Outcome.NO_WORK_CONTEXT
				&& (authentication != null || accessContextTicket != null || ticketExpiresAt != null)) {
			throw new IllegalArgumentException("No-work-context outcome cannot contain a session or ticket");
		}
	}

	public static IdentitySignInResult authenticated(AuthenticationResult result) {
		return new IdentitySignInResult(Outcome.SESSION_ESTABLISHED, Objects.requireNonNull(result), null, null);
	}

	public static IdentitySignInResult selectionRequired(String ticket, Instant expiresAt) {
		return new IdentitySignInResult(Outcome.CONTEXT_SELECTION_REQUIRED, null, ticket, expiresAt);
	}

	public static IdentitySignInResult noWorkContext() {
		return new IdentitySignInResult(Outcome.NO_WORK_CONTEXT, null, null, null);
	}

	@Override
	public String toString() {
		return "IdentitySignInResult[outcome=" + outcome + ", authentication="
				+ (authentication == null ? "null" : "[REDACTED]") + ", accessContextTicket="
				+ (accessContextTicket == null ? "null" : "[REDACTED]") + ", ticketExpiresAt=" + ticketExpiresAt + "]";
	}
}
