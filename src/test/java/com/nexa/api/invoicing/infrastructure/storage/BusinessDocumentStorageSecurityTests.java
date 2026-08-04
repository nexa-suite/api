package com.nexa.api.invoicing.infrastructure.storage;

import com.nexa.api.invoicing.infrastructure.security.ClamAvContentScannerAdapter;
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
        var scanner = new ClamAvContentScannerAdapter(new MockEnvironment());

        var clean = scanner.scan(new ByteArrayInputStream("%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)));
        var malware = scanner.scan(new ByteArrayInputStream("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII)));

        assertThat(clean.clean()).isTrue();
        assertThat(clean.detectedContentType()).isEqualTo("application/pdf");
        assertThat(malware.clean()).isFalse();
        assertThat(malware.reason()).isEqualTo("MALWARE_SIGNATURE");
    }
}
