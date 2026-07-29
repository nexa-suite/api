package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColdChainRequirementTests {
	@Test
	void parsesLegacyValuesCaseInsensitively() {
		assertThat(ColdChainRequirement.fromLegacyValue("None")).isEqualTo(ColdChainRequirement.NONE);
		assertThat(ColdChainRequirement.fromLegacyValue(" refrigerated ")).isEqualTo(ColdChainRequirement.REFRIGERATED);
		assertThat(ColdChainRequirement.fromLegacyValue("FROZEN")).isEqualTo(ColdChainRequirement.FROZEN);
	}

	@Test
	void rejectsNullBlankAndUnknownValues() {
		assertThatThrownBy(() -> ColdChainRequirement.fromLegacyValue(null)).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> ColdChainRequirement.fromLegacyValue(" ")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> ColdChainRequirement.fromLegacyValue("Ambient")).isInstanceOf(CatalogInvariantViolation.class);
	}
}
