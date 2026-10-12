package com.nexa.api.bootstrap.runtime.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBusinessDatabaseBaselineGeneratorTests {
	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();

	@Test
	void v3TenantProjectionRemainsPinnedToCentralV146AndExcludesLaterWalletTables() throws Exception {
		var tables = TenantBusinessDatabaseBaselineGenerator.readManifest(REPOSITORY_ROOT);

		assertThat(tables).hasSize(167);
		assertThat(tables).noneMatch(table -> table.name().startsWith("payments.buyer_wallet_"));
		assertThat(tables).anyMatch(table -> table.name().equals("payments.payment"));
	}

	@Test
	void postV146OwnershipEvidenceDoesNotChangePublishedV3OwnershipDigest() throws Exception {
		String digest = TenantBusinessDatabaseBaselineGenerator.publishedV146OwnershipDigest(
				REPOSITORY_ROOT.resolve(TenantBusinessDatabaseBaselineGenerator.CANONICAL_OWNERSHIP_PATH));

		assertThat(digest).isEqualTo("8ee162139eb3f1340ec22dc6c48a94e962e7a21180220bd48aa49cdaa6c7cca5");
	}
}
