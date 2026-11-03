package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OrganizationLegalAddress;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OrganizationProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationProfileTests {
	@Test
	void acceptsElevenDigitRucWithoutClaimingRegistryValidation() {
		OrganizationProfile profile = profile("00000000000", null);

		assertThat(profile.ruc()).isEqualTo("00000000000");
	}

	@Test
	void rejectsRucThatIsNotExactlyElevenDigits() {
		assertThatThrownBy(() -> profile("1234567890A", null))
				.isInstanceOf(TenantManagementInvariantViolation.class)
				.hasMessageContaining("11 digits");
	}

	@Test
	void validatesStructuredAddressFieldsAndPeruCodeHierarchy() {
		OrganizationLegalAddress address = new OrganizationLegalAddress("pe", " Av. Ejemplo 123 ",
				"15", "1501", "150101", "AV", "Ejemplo", "123", "4B", "15001", "Frente al parque");

		assertThat(address.countryCode()).isEqualTo("PE");
		assertThat(address.addressLine()).isEqualTo("Av. Ejemplo 123");
		assertThatThrownBy(() -> new OrganizationLegalAddress("PE", "Av. Ejemplo 123",
				"15", "1601", "160101", "AV", "Ejemplo", "123", null, null, null))
				.isInstanceOf(TenantManagementInvariantViolation.class)
				.hasMessageContaining("invalid structure");
	}

	private static OrganizationProfile profile(String ruc, OrganizationLegalAddress legalAddress) {
		return new OrganizationProfile("Nexa Company", "Nexa", null, "DISTRIBUTOR", 0, ruc, legalAddress);
	}
}
