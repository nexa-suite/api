package com.nexa.api.bootstrap.local;

import com.nexa.api.bootstrap.local.seed.ClientAccountSeedLoader;
import com.nexa.api.bootstrap.local.seed.ClientAccountSeedRecord;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountAddress;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTenantBusinessFixtureSeederTests {
	@Test
	void reviewedBuyerAddressSeedCanBeRehydratedWithRequiredGeography() {
		ClientAccountSeedRecord seed = new ClientAccountSeedLoader(new tools.jackson.databind.ObjectMapper()).load().stream()
				.filter(record -> "CLI-001".equals(record.code())).findFirst().orElseThrow();

		var address = LocalTenantBusinessFixtureSeeder.addressForBuyerSeed(seed);
		ClientAccountAddress rehydrated = ClientAccountAddress.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "fixture-client-account", "Buyer delivery · CLI-001", address, true, true, 0);

		assertThat(rehydrated.address().departmentCode()).isEqualTo("15");
		assertThat(rehydrated.address().provinceCode()).isEqualTo("1501");
		assertThat(rehydrated.address().districtCode()).isEqualTo("150122");
		assertThat(rehydrated.address().line()).isEqualTo("Av. Jose Pardo 610");
	}
}
