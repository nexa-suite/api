package com.nexa.api.tenantaccessgovernance.iam.application.model;

import java.time.Instant;
import java.util.Objects;

public record IdentitySignInResult(Outcome outcome, AuthenticationResult authentication,
		String accessContextTicket, Instant ticketExpiresAt) {
	public enum Outcome { AUTHENTICATED, ACCESS_CONTEXT_SELECTION_REQUIRED }

	public IdentitySignInResult {
		Objects.requireNonNull(outcome, "Identity sign-in outcome is required");
		if (outcome == Outcome.AUTHENTICATED && (authentication == null || accessContextTicket != null || ticketExpiresAt != null)) {
			throw new IllegalArgumentException("Authenticated outcome must contain only an authoritative session");
		}
		if (outcome == Outcome.ACCESS_CONTEXT_SELECTION_REQUIRED
				&& (authentication != null || accessContextTicket == null || accessContextTicket.isBlank() || ticketExpiresAt == null)) {
			throw new IllegalArgumentException("Context selection outcome must contain a pre-context ticket and expiry");
		}
	}

	public static IdentitySignInResult authenticated(AuthenticationResult result) {
		return new IdentitySignInResult(Outcome.AUTHENTICATED, Objects.requireNonNull(result), null, null);
	}

	public static IdentitySignInResult selectionRequired(String ticket, Instant expiresAt) {
		return new IdentitySignInResult(Outcome.ACCESS_CONTEXT_SELECTION_REQUIRED, null, ticket, expiresAt);
	}

	@Override
	public String toString() {
		return "IdentitySignInResult[outcome=" + outcome + ", authentication="
				+ (authentication == null ? "null" : "[REDACTED]") + ", accessContextTicket="
				+ (accessContextTicket == null ? "null" : "[REDACTED]") + ", ticketExpiresAt=" + ticketExpiresAt + "]";
	}
}
