package com.nexa.api.businessdocuments.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "nexa.minio.integration.enabled", matches = "true")
class MinioObjectStorageIntegrationTests {
    @Test
    void writesReadsAndDeletesAnObjectThroughTheMinioService() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nexa.object-storage.endpoint", requiredEnvironment("NEXA_OBJECT_STORAGE_ENDPOINT"))
                .withProperty("nexa.object-storage.bucket", requiredEnvironment("NEXA_OBJECT_STORAGE_BUCKET"))
                .withProperty("nexa.object-storage.access-key", requiredEnvironment("NEXA_MINIO_ROOT_USER"))
                .withProperty("nexa.object-storage.secret-key", requiredEnvironment("NEXA_MINIO_ROOT_PASSWORD"))
                .withProperty("nexa.object-storage.region", "us-east-1")
                .withProperty("nexa.object-storage.timeout-ms", "5000");
        S3CompatibleObjectStorageAdapter storage = new S3CompatibleObjectStorageAdapter(environment);
        byte[] content = "%PDF-1.7\nprivate-minio-object".getBytes(StandardCharsets.US_ASCII);
        String key = "integration/" + UUID.randomUUID() + "/private.pdf";

        try {
            var stored = storage.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
            try (var downloaded = storage.open(key)) {
                assertThat(downloaded.readAllBytes()).containsExactly(content);
            }
            assertThat(stored.checksumSha256())
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } finally {
            storage.delete(key);
        }
        assertThatThrownBy(() -> storage.open(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP 404");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required MinIO integration environment variable is missing: " + name);
        }
        return value;
    }
}
