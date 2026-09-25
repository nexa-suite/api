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
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String BUCKET = "nexa-test-private";

    @Test
    void writesReadsAndDeletesAPrivateObjectThroughS3Emulator() throws Exception {
        try (GenericContainer<?> s3 = new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.10.0"))
                .withExposedPorts(4566)
                .withEnv("SERVICES", "s3")
                .withEnv("AWS_DEFAULT_REGION", "us-east-1")
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200))) {
            s3.start();
            assertThat(s3.execInContainer("awslocal", "s3api", "create-bucket", "--bucket", BUCKET)
                    .getExitCode()).isZero();

            MockEnvironment environment = new MockEnvironment()
                    .withProperty("nexa.object-storage.endpoint", "http://" + s3.getHost() + ":" + s3.getMappedPort(4566))
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
