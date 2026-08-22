package com.nexa.api.invoicing.infrastructure.security;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Prevents a non-local deployment from starting with an unreachable scanner boundary. */
@Component
@Profile("!test")
public final class ClamAvRuntimeConfigurationValidator {
    private static final Set<String> SUPPORTED = Set.of("network", "deterministic-local");

    public ClamAvRuntimeConfigurationValidator(Environment environment) {
        String mode = environment.getProperty("nexa.clamav.mode", "network").trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("network".equals(mode) && environment.getProperty("nexa.clamav.host", "").isBlank()) {
            throw new IllegalStateException("NEXA_CLAMAV_HOST is required when malware scanning uses network mode");
        }
        if (!SUPPORTED.contains(mode)) {
            throw new IllegalStateException("Unsupported ClamAV mode '" + mode + "'; use network or deterministic-local");
        }
        if ("deterministic-local".equals(mode) && !environment.acceptsProfiles("local")) {
            throw new IllegalStateException("Deterministic malware scanning requires the local profile");
        }
    }

}
