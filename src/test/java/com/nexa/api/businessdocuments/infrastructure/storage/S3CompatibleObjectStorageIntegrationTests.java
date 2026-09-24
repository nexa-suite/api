package com.nexa.api.businessdocuments.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class S3CompatibleObjectStorageIntegrationTests {
    private static final String ACCESS_KEY = "nexa-test-access";
    private static final String SECRET_KEY = "nexa-test-secret-key";
    private static final String BUCKET = "nexa-test-private";

    @Test
    void writesReadsAndDeletesAPrivateObjectThroughMinio() throws Exception {
        try (GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-10-13T13-34-11Z"))
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200))) {
            minio.start();
            assertThat(minio.execInContainer("mc", "alias", "set", "test", "http://127.0.0.1:9000", ACCESS_KEY, SECRET_KEY)
                    .getExitCode()).isZero();
            assertThat(minio.execInContainer("mc", "mb", "test/" + BUCKET).getExitCode()).isZero();

            MockEnvironment environment = new MockEnvironment()
                    .withProperty("nexa.object-storage.endpoint", "http://" + minio.getHost() + ":" + minio.getMappedPort(9000))
                    .withProperty("nexa.object-storage.bucket", BUCKET)
                    .withProperty("nexa.object-storage.access-key", ACCESS_KEY)
                    .withProperty("nexa.object-storage.secret-key", SECRET_KEY)
                    .withProperty("nexa.object-storage.region", "us-east-1")
                    .withProperty("nexa.object-storage.timeout-ms", "5000");
            S3CompatibleObjectStorageAdapter storage = new S3CompatibleObjectStorageAdapter(environment);
            byte[] content = "%PDF-1.7\nprivate-object".getBytes(StandardCharsets.US_ASCII);
            String key = "integration/" + UUID.randomUUID() + "/private.pdf";

            var stored = storage.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
            assertThat(storage.open(key).readAllBytes()).containsExactly(content);
            assertThat(stored.checksumSha256()).hasSize(64);
            storage.delete(key);
        }
    }
}
