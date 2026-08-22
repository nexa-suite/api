package com.nexa.api.invoicing.infrastructure.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;

/** Prevents a production-like runtime from silently selecting local filesystem storage. */
@Component
@Profile("!test")
public final class ObjectStorageRuntimeConfigurationValidator {
    public ObjectStorageRuntimeConfigurationValidator(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local", "s3", "minio"))) {
            throw new IllegalStateException("Object storage profile is required; activate local, s3 or minio");
        }
        if (environment.acceptsProfiles(Profiles.of("s3", "minio"))) {
            required(environment, "nexa.object-storage.endpoint", "endpoint");
            required(environment, "nexa.object-storage.bucket", "bucket");
            required(environment, "nexa.object-storage.access-key", "access key");
            required(environment, "nexa.object-storage.secret-key", "secret key");
            required(environment, "nexa.object-storage.region", "region");
            String endpoint = required(environment, "nexa.object-storage.endpoint", "endpoint");
            try {
                URI uri = URI.create(endpoint);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    throw new IllegalStateException("nexa.object-storage.endpoint must be an absolute URI with a host");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("nexa.object-storage.endpoint must be an absolute URI with a host", exception);
            }
            positive(environment, "nexa.object-storage.timeout-ms", "5000");
        }
    }

    private static String required(Environment environment, String key, String label) {
        String value = environment.getProperty(key, "");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for the active durable object-storage profile (" + label + ")");
        }
        return value.trim();
    }

    private static void positive(Environment environment, String key, String defaultValue) {
        String raw = environment.getProperty(key, defaultValue);
        try {
            if (Integer.parseInt(raw) <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be a positive integer", exception);
        }
    }
}
