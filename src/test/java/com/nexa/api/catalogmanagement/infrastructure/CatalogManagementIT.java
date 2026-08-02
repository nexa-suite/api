package com.nexa.api.catalogmanagement.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CatalogManagementIT extends PostgresIntegrationSupport {
	private static final JsonMapper JSON = JsonMapper.shared();

	@Test
	void buyerReadsPersistentCatalogWithCoarseAvailabilityAndPrice() throws Exception {
		String token = accessToken(BUYER_EMAIL, "PORTAL");

		mockMvc.perform(get("/api/v1/catalog-items")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("page", "0")
				.param("size", "5"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.items").isNotEmpty())
			.andExpect(jsonPath("$.items[0].unitPrice.currency").value("PEN"))
			.andExpect(jsonPath("$.items[0].availabilityStatus").value("OUT_OF_STOCK"))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
	}

	@Test
	void ownerCreatesAndReplaysCategoryThenMustUseIfMatchForUpdate() throws Exception {
		String token = accessToken(OWNER_EMAIL, "PLATFORM");
		String slug = "it-category-" + UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"slug\":\"" + slug + "\",\"name\":\"Integration category\",\"description\":\"Catalog test\"}";
		String key = "catalog-category-" + UUID.randomUUID();

		MvcResult first = mockMvc.perform(post("/api/v1/catalog/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn();
		JsonNode firstJson = JSON.readTree(first.getResponse().getContentAsString());
		String categoryId = firstJson.get("id").asText();

		MvcResult replay = mockMvc.perform(post("/api/v1/catalog/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(JSON.readTree(replay.getResponse().getContentAsString()).get("id").asText())
				.isEqualTo(categoryId);

		mockMvc.perform(patch("/api/v1/catalog/categories/" + categoryId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isPreconditionRequired())
			.andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

		mockMvc.perform(patch("/api/v1/catalog/categories/" + categoryId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(HttpHeaders.IF_MATCH, first.getResponse().getHeader(HttpHeaders.ETAG))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.replace("Integration category", "Integration category updated")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	void warehouseCanReadCatalogButCannotManageTaxonomy() throws Exception {
		String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

		mockMvc.perform(get("/api/v1/catalog/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("page", "0")
				.param("size", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(greaterThan(0)));

		mockMvc.perform(post("/api/v1/catalog/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "warehouse-catalog-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"slug\":\"warehouse-forbidden\",\"name\":\"Forbidden\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}
}
