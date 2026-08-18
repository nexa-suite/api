package com.nexa.api.invoicing.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class S3CompatibleObjectStorageIntegrationTests {
    @Test
    void writesReadsAndDeletesAPrivateObjectThroughMinio() throws Exception {
        Assumptions.assumeTrue(
                !System.getProperty("nexa.object-storage.endpoint", "").isBlank(),
                "MinIO endpoint is not configured for the optional object-storage integration test"
        );
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nexa.object-storage.endpoint", required("nexa.object-storage.endpoint"))
                .withProperty("nexa.object-storage.bucket", required("nexa.object-storage.bucket"))
                .withProperty("nexa.object-storage.access-key", required("nexa.object-storage.access-key"))
                .withProperty("nexa.object-storage.secret-key", required("nexa.object-storage.secret-key"))
                .withProperty("nexa.object-storage.region", System.getProperty("nexa.object-storage.region", "us-east-1"))
                .withProperty("nexa.object-storage.timeout-ms", System.getProperty("nexa.object-storage.timeout-ms", "2000"));
        S3CompatibleObjectStorageAdapter storage = new S3CompatibleObjectStorageAdapter(environment);
        byte[] content = "%PDF-1.7\nwave-0-private-object".getBytes(StandardCharsets.US_ASCII);
        String key = "wave-0/" + UUID.randomUUID() + "/private.pdf";

        var stored = storage.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
        try {
            assertThat(storage.open(key).readAllBytes()).containsExactly(content);
            assertThat(stored.checksumSha256()).hasSize(64);
        } finally {
            storage.delete(key);
        }
    }

    private static String required(String key) {
        String value = System.getProperty(key, "");
        if (value.isBlank()) throw new IllegalStateException("Missing integration property " + key);
        return value;
    }
}
