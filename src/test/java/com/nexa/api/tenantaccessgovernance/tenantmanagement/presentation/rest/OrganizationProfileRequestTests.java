package com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationProfileRequestTests {
	@Test
	void oldRequestPayloadDoesNotSetNewFieldPresenceFlags() throws Exception {
		var request = JsonMapper.shared().readValue("""
				{"legalName":"Nexa Company","displayName":"Nexa","businessIdentifier":"LEGACY",
				 "operationCategory":"DISTRIBUTOR"}
				""", TenantConfigurationController.OrganizationProfileRequest.class);

		TenantConfigurationModels.OrganizationProfileUpdate command = request.toCommand();
		assertThat(command.rucProvided()).isFalse();
		assertThat(command.legalAddressProvided()).isFalse();
	}

	@Test
	void explicitNullCanClearNewProfileFields() throws Exception {
		var request = JsonMapper.shared().readValue("""
				{"legalName":"Nexa Company","displayName":"Nexa","businessIdentifier":"LEGACY",
				 "operationCategory":"DISTRIBUTOR","ruc":null,"legalAddress":null}
				""", TenantConfigurationController.OrganizationProfileRequest.class);

		TenantConfigurationModels.OrganizationProfileUpdate command = request.toCommand();
		assertThat(command.rucProvided()).isTrue();
		assertThat(command.ruc()).isNull();
		assertThat(command.legalAddressProvided()).isTrue();
		assertThat(command.legalAddress()).isNull();
	}

	@Test
	void parsesStructuredLegalAddressIntoTypedProfileData() throws Exception {
		var request = JsonMapper.shared().readValue("""
				{"legalName":"Nexa Company","displayName":"Nexa","businessIdentifier":"LEGACY",
				 "operationCategory":"DISTRIBUTOR","legalAddress":{"countryCode":"PE",
				 "addressLine":"Av. Ejemplo 123","departmentCode":"15","provinceCode":"1501",
				 "districtCode":"150101"}}
				""", TenantConfigurationController.OrganizationProfileRequest.class);

		TenantConfigurationModels.OrganizationProfileUpdate command = request.toCommand();
		assertThat(command.legalAddressProvided()).isTrue();
		assertThat(command.legalAddress().districtCode()).isEqualTo("150101");
	}
}
