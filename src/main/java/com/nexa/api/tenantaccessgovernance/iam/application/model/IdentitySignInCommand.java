package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record IdentitySignInCommand(LoginIdentifier login, String password, ClientSurface surface, String clientFingerprint) {
	public IdentitySignInCommand {
		Objects.requireNonNull(login, "Login identifier is required");
		if (password == null || password.isBlank()) throw new IllegalArgumentException("Password is required");
		Objects.requireNonNull(surface, "Client surface is required");
		if (clientFingerprint == null || clientFingerprint.isBlank()) clientFingerprint = "unknown";
		clientFingerprint = clientFingerprint.trim();
		if (clientFingerprint.length() > 128) throw new IllegalArgumentException("Client fingerprint is too long");
	}

	public IdentitySignInCommand(String identifier, String password, ClientSurface surface, String clientFingerprint) {
		this(new LoginIdentifier(identifier), password, surface, clientFingerprint);
	}

	@Override
	public String toString() {
		return "IdentitySignInCommand[login=" + login + ", password=[REDACTED], surface=" + surface + "]";
	}
}
