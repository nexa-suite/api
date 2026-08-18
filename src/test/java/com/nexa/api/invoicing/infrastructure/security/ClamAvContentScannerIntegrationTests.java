package com.nexa.api.invoicing.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the network ClamAV adapter against the Docker service, not the fallback detector. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ClamAvContentScannerIntegrationTests {
    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);

    @Test
    void cleanContentIsAcceptedAndEicarIsRejectedByClamAv() {
        var environment = new MockEnvironment()
                .withProperty("nexa.clamav.host", System.getProperty("nexa.clamav.host", "127.0.0.1"))
                .withProperty("nexa.clamav.port", System.getProperty("nexa.clamav.port", "3310"));
        var scanner = new ClamAvContentScannerAdapter(environment);

        var clean = scanner.scan(new ByteArrayInputStream("%PDF-1.7\\nclean".getBytes(StandardCharsets.US_ASCII)));
        var malware = scanner.scan(new ByteArrayInputStream(EICAR));

        assertThat(clean.clean()).isTrue();
        assertThat(malware.clean()).isFalse();
        assertThat(malware.reason()).isEqualTo("MALWARE_SIGNATURE");
    }
}
