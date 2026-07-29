package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record SignInCommand(LoginIdentifier login, String password, ClientSurface surface) {
	public SignInCommand {
		Objects.requireNonNull(login, "Login identifier is required");
		if (password == null || password.isBlank()) throw new IllegalArgumentException("Password is required");
		Objects.requireNonNull(surface, "Client surface is required");
	}

	public SignInCommand(String login, String password, ClientSurface surface) {
		this(new LoginIdentifier(login), password, surface);
	}
}
