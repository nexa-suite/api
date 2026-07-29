package com.nexa.api.shared.infrastructure.security;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Profile("!local & !test")
public final class SecureRuntimeConfigurationValidator {
	public SecureRuntimeConfigurationValidator(NexaSecurityProperties properties, Environment environment) {
		requireText(properties.getIssuer(), "nexa.security.issuer");
		requireText(properties.getAudience(), "nexa.security.audience");
		if (properties.isAllowEphemeralKeys()) fail("ephemeral RSA keys are not allowed outside local/test");
		if (properties.getRsaPublicKey() == null || properties.getRsaPrivateKey() == null
				|| !Files.isRegularFile(Path.of(properties.getRsaPublicKey()))
				|| !Files.isRegularFile(Path.of(properties.getRsaPrivateKey()))) {
			fail("RSA key paths must point to existing files");
		}
		if (!properties.isRefreshCookieSecure()) fail("refresh cookie secure mode must be enabled");
		if (properties.getAllowedOrigins() == null || properties.getAllowedOrigins().stream().anyMatch("*"::equals)) {
			fail("wildcard CORS is not allowed");
		}
		if (Boolean.parseBoolean(environment.getProperty("nexa.dev-bootstrap.enabled", "false"))
				|| Boolean.parseBoolean(environment.getProperty("NEXA_DEV_BOOTSTRAP_ENABLED", "false"))) {
			fail("bootstrap must be disabled");
		}
		for (String value : new String[] { properties.getIssuer(), properties.getAudience(), properties.getRsaPublicKey(), properties.getRsaPrivateKey() }) {
			if (value != null && (value.contains("${") || value.equalsIgnoreCase("changeme") || value.equalsIgnoreCase("secret"))) {
				fail("placeholder security configuration detected");
			}
		}
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank() || value.contains("${")) fail(name + " is required");
	}

	private static void fail(String message) {
		throw new IllegalStateException("Invalid secure runtime configuration: " + message);
	}
}
