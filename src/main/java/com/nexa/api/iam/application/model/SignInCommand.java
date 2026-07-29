package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record SignInCommand(LoginIdentifier login, String password, String workspaceSlug, ClientSurface surface) {
	public SignInCommand {
		Objects.requireNonNull(login, "Login identifier is required");
		if (password == null || password.isBlank()) throw new IllegalArgumentException("Password is required");
		Objects.requireNonNull(surface, "Client surface is required");
		if (workspaceSlug != null && workspaceSlug.isBlank()) workspaceSlug = null;
		if (workspaceSlug != null) workspaceSlug = workspaceSlug.trim().toLowerCase(java.util.Locale.ROOT);
	}

	public SignInCommand(String login, String password, ClientSurface surface) {
		this(new LoginIdentifier(login), password, null, surface);
	}

	public SignInCommand(String login, String password, String workspaceSlug, ClientSurface surface) {
		this(new LoginIdentifier(login), password, workspaceSlug, surface);
	}
}
