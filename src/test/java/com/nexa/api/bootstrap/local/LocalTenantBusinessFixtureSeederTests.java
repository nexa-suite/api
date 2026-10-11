package com.nexa.api.bootstrap.local;

import com.nexa.api.bootstrap.local.seed.ClientAccountSeedLoader;
import com.nexa.api.bootstrap.local.seed.ClientAccountSeedRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTenantBusinessFixtureSeederTests {
	@Test
	void reviewedBuyerAddressSeedHasRequiredGeography() {
		ClientAccountSeedRecord seed = new ClientAccountSeedLoader(new tools.jackson.databind.ObjectMapper()).load().stream()
				.filter(record -> "CLI-001".equals(record.code())).findFirst().orElseThrow();

		var address = LocalTenantBusinessFixtureSeeder.addressForBuyerSeed(seed);

		assertThat(address.departmentCode()).isEqualTo("15");
		assertThat(address.provinceCode()).isEqualTo("1501");
		assertThat(address.districtCode()).isEqualTo("150122");
		assertThat(address.line()).isEqualTo("Av. Jose Pardo 610");
	}
}
