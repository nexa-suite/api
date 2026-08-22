package com.nexa.api.invoicing.infrastructure.storage;

import com.nexa.api.invoicing.infrastructure.security.ClamAvContentScannerAdapter;
import com.nexa.api.invoicing.infrastructure.security.ClamAvRuntimeConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDocumentStorageSecurityTests {
    @TempDir
    Path root;

    @Test
    void localStorageStreamsBoundedPrivateObjectsAndUsesContentChecksum() throws Exception {
        var environment = new MockEnvironment().withProperty("nexa.object-storage.root", root.toString());
        var storage = new LocalObjectStorageAdapter(environment);
        byte[] content = "%PDF-1.7\nprivate document".getBytes(StandardCharsets.US_ASCII);

        var stored = storage.put("documents/tenant/document-000000000001.pdf", new ByteArrayInputStream(content), content.length, "application/pdf");

        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(stored.checksumSha256()).hasSize(64);
        assertThat(storage.open(stored.objectKey()).readAllBytes()).containsExactly(content);
        assertThatThrownBy(() -> storage.open("../escape")).isInstanceOf(IllegalArgumentException.class);
        storage.delete(stored.objectKey());
        assertThat(Files.exists(root.resolve(stored.objectKey()))).isFalse();
    }

    @Test
    void clamBoundaryRejectsEicarAndDetectsPdfFromStream() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("nexa.clamav.mode", "deterministic-local");
        var scanner = new ClamAvContentScannerAdapter(environment);

        var clean = scanner.scan(new ByteArrayInputStream("%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)));
        var malware = scanner.scan(new ByteArrayInputStream("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII)));

        assertThat(clean.clean()).isTrue();
        assertThat(clean.detectedContentType()).isEqualTo("application/pdf");
        assertThat(malware.clean()).isFalse();
        assertThat(malware.reason()).isEqualTo("MALWARE_SIGNATURE");
    }

    @Test
    void networkModeFailsClosedWhenScannerEndpointIsNotConfigured() {
        var scanner = new ClamAvContentScannerAdapter(new MockEnvironment()
                .withProperty("nexa.clamav.mode", "network")
                .withProperty("nexa.clamav.host", ""));

        var result = scanner.scan(new ByteArrayInputStream("%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)));

        assertThat(result.clean()).isFalse();
        assertThat(result.reason()).isEqualTo("MALWARE_SCANNER_UNAVAILABLE");
    }

    @Test
    void networkModeFailsClosedWhenConfiguredScannerIsUnavailable() {
        var scanner = new ClamAvContentScannerAdapter(new MockEnvironment()
                .withProperty("nexa.clamav.mode", "network")
                .withProperty("nexa.clamav.host", "127.0.0.1")
                .withProperty("nexa.clamav.port", "39999")
                .withProperty("nexa.clamav.connect-timeout-ms", "100")
                .withProperty("nexa.clamav.read-timeout-ms", "100"));

        var result = scanner.scan(new ByteArrayInputStream("%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)));

        assertThat(result.clean()).isFalse();
        assertThat(result.reason()).isEqualTo("MALWARE_SCANNER_UNAVAILABLE");
    }

    @Test
    void scannerStartupRequiresAnExplicitBoundaryOutsideLocalMode() {
        assertThatThrownBy(() -> new ClamAvRuntimeConfigurationValidator(new MockEnvironment()
                .withProperty("nexa.clamav.mode", "network")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NEXA_CLAMAV_HOST is required when malware scanning uses network mode");

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        local.setProperty("nexa.clamav.mode", "deterministic-local");
        new ClamAvRuntimeConfigurationValidator(local);
    }
}
