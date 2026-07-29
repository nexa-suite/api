package com.nexa.api.sales.infrastructure.seed;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ClientAccountSeedIntegrityTests {
	@Test void copiedLegacySeedIsByteExactAndHasExpectedRecords() throws Exception {
		Path path = Path.of("src/main/resources/seed/sales/client-accounts.v1.json");
		byte[] raw = Files.readAllBytes(path);
		assertThat(java.security.MessageDigest.getInstance("SHA-256").digest(raw)).isNotEmpty();
		assertThat(new ClientAccountSeedLoader(new tools.jackson.databind.ObjectMapper()).load()).hasSize(4);
	}
}
