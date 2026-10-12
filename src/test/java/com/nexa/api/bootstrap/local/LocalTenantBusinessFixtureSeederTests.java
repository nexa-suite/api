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
		assertThat(address.districtCode()).isEqualTo("150121");
		assertThat(address.line()).isEqualTo("Av. Sucre 1992");
		assertThat(address.latitude()).isEqualByComparingTo("-12.0725");
		assertThat(address.longitude()).isEqualByComparingTo("-77.0685");
		assertThat(address.source()).isEqualTo("MAP_PIN");
	}

	@Test
	void otherBuyerSeedsDoNotReuseTheDemoBuyersCoordinates() {
		ClientAccountSeedRecord demoBuyer = new ClientAccountSeedLoader(new tools.jackson.databind.ObjectMapper()).load()
				.stream().filter(record -> "CLI-001".equals(record.code())).findFirst().orElseThrow();
		ClientAccountSeedRecord otherBuyer = new ClientAccountSeedRecord("CLI-002", demoBuyer.businessName(),
				demoBuyer.commercialName(), demoBuyer.ruc(), demoBuyer.segment(), demoBuyer.contact(),
				demoBuyer.contactEmail(), demoBuyer.phone(), demoBuyer.paymentCondition(), demoBuyer.monthlyCreditLimit(),
				demoBuyer.monthlyCreditUsed(), demoBuyer.monthlyCreditStatus(), demoBuyer.deliveryPreference(),
				demoBuyer.address(), demoBuyer.district(), demoBuyer.province(), demoBuyer.deliveryReference(),
				demoBuyer.departmentCode(), demoBuyer.provinceCode(), demoBuyer.districtCode(), true,
				demoBuyer.sellerWorkspaceEmail(), demoBuyer.status());

		var address = LocalTenantBusinessFixtureSeeder.addressForBuyerSeed(otherBuyer);

		assertThat(address.latitude()).isNull();
		assertThat(address.longitude()).isNull();
		assertThat(address.source()).isEqualTo("MANUAL");
	}
}
