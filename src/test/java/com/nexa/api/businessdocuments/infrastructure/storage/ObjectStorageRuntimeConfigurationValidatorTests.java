package com.nexa.api.businessdocuments.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStorageRuntimeConfigurationValidatorTests {
    @Test
    void rejectsDurableProfileWithoutExplicitEndpoint() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("s3");

        assertThatThrownBy(() -> new ObjectStorageRuntimeConfigurationValidator(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nexa.object-storage.endpoint is required");
    }

    @Test
    void rejectsDurableProfileWithInvalidEndpoint() {
        MockEnvironment environment = durableEnvironment();
        environment.setProperty("nexa.object-storage.endpoint", "localhost:9000");

        assertThatThrownBy(() -> new ObjectStorageRuntimeConfigurationValidator(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nexa.object-storage.endpoint must be an absolute URI with a host");
    }

    @Test
    void acceptsExplicitMinioConfiguration() {
        new ObjectStorageRuntimeConfigurationValidator(durableEnvironment());
    }

    private static MockEnvironment durableEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("minio");
        environment.setProperty("nexa.object-storage.endpoint", "http://localhost:9000");
        environment.setProperty("nexa.object-storage.bucket", "nexa-private");
        environment.setProperty("nexa.object-storage.access-key", "test-access");
        environment.setProperty("nexa.object-storage.secret-key", "test-secret");
        environment.setProperty("nexa.object-storage.region", "us-east-1");
        environment.setProperty("nexa.object-storage.timeout-ms", "5000");
        return environment;
    }
}
