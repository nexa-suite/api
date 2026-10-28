package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OrganizationProfileHttpIT extends PostgresIntegrationSupport {
	private static final String PROFILE_WITH_LEGAL_DATA = """
			{"legalName":"ICISA Test","displayName":"ICISA Legal Profile",
			 "businessIdentifier":"LEGACY-IDENTIFIER","operationCategory":"B2B_COLD_CHAIN_DISTRIBUTOR",
			 "ruc":"00000000000","legalAddress":{"countryCode":"PE","addressLine":"Av. Ejemplo 123",
			 "departmentCode":"15","provinceCode":"1501","districtCode":"150101","roadType":"AV",
			 "streetName":"Ejemplo","streetNumber":"123","interior":"4B","postalCode":"15001",
			 "reference":"Referencia privada"}}
			""";
	private static final String LEGACY_PROFILE = """
			{"legalName":"ICISA Test","displayName":"ICISA Legacy Update",
			 "businessIdentifier":"LEGACY-IDENTIFIER","operationCategory":"B2B_COLD_CHAIN_DISTRIBUTOR"}
			""";

	@Test
	void storesStructuredLegalProfileAndKeepsOldPatchClientsCompatible() throws Exception {
		String owner = accessToken(OWNER_EMAIL, "PLATFORM");
		var initial = mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn();

		var created = mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner)
					.header("If-Match", initial.getResponse().getHeader("ETag"))
					.contentType(MediaType.APPLICATION_JSON).content(PROFILE_WITH_LEGAL_DATA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ruc").value("00000000000"))
				.andExpect(jsonPath("$.legalAddress.countryCode").value("PE"))
				.andExpect(jsonPath("$.legalAddress.districtCode").value("150101"))
				.andReturn();

		var legacyUpdate = mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner)
					.header("If-Match", created.getResponse().getHeader("ETag"))
					.contentType(MediaType.APPLICATION_JSON).content(LEGACY_PROFILE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ruc").value("00000000000"))
				.andExpect(jsonPath("$.legalAddress.addressLine").value("Av. Ejemplo 123"))
				.andReturn();

		assertThat(jdbc.queryForObject("select ruc from tenant_management.organization_settings where tenant_id=?",
				String.class, java.util.UUID.fromString(tenantId()))).isEqualTo("00000000000");
		String audit = jdbc.queryForObject("select metadata_json::text from iam.security_audit_event "
				+ "where event_type='ORGANIZATION_UPDATED' order by occurred_at desc limit 1", String.class);
		assertThat(audit).doesNotContain("00000000000", "Referencia privada", "Av. Ejemplo 123");

		mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner)
					.header("If-Match", legacyUpdate.getResponse().getHeader("ETag"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"legalName":"ICISA Test","displayName":"ICISA Legal Profile",
							 "businessIdentifier":"LEGACY-IDENTIFIER","operationCategory":"B2B_COLD_CHAIN_DISTRIBUTOR",
							 "ruc":null,"legalAddress":null}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ruc").doesNotExist());
		assertThat(jdbc.queryForObject("select ruc from tenant_management.organization_settings where tenant_id=?",
				String.class, java.util.UUID.fromString(tenantId()))).isNull();
	}

	@Test
	void rejectsMalformedRucWithoutAdvancingProfileVersion() throws Exception {
		String owner = accessToken(OWNER_EMAIL, "PLATFORM");
		var current = mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn();
		String beforeVersion = current.getResponse().getHeader("ETag");

		mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner)
					.header("If-Match", beforeVersion).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"legalName":"ICISA Test","displayName":"Invalid RUC",
							 "businessIdentifier":"LEGACY-IDENTIFIER","operationCategory":"B2B_COLD_CHAIN_DISTRIBUTOR",
							 "ruc":"1234567890A"}
							"""))
				.andExpect(status().isBadRequest());

		var unchanged = mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn();
		assertThat(unchanged.getResponse().getHeader("ETag")).isEqualTo(beforeVersion);
	}
}
