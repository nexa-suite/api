package com.nexa.api.catalogcommercialpolicy.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
	@Autowired
	private CatalogPersistenceBootstrap seed;
	private UUID previewProduct;
	private UUID previewPromotion;

	@AfterEach
	void removePreviewFixture() {
		if (previewPromotion != null) {
			jdbc.update("delete from catalog_management.promotion_product where promotion_id=?", previewPromotion);
			jdbc.update("delete from catalog_management.promotion_category where promotion_id=?", previewPromotion);
			jdbc.update("delete from catalog_management.promotion_client_account where promotion_id=?", previewPromotion);
			jdbc.update("delete from catalog_management.promotion_rule where promotion_id=?", previewPromotion);
			jdbc.update("delete from catalog_management.promotion where id=?", previewPromotion);
		}
		if (previewProduct != null) {
			jdbc.update("delete from catalog_management.product_price where product_id=?", previewProduct);
			jdbc.update("delete from catalog_management.product_presentation where product_id=?", previewProduct);
			jdbc.update("delete from catalog_management.product_visibility where product_id=?", previewProduct);
			jdbc.update("delete from catalog_management.product where id=?", previewProduct);
		}
	}

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
			.andExpect(jsonPath("$.items[0].availabilityStatus")
					.value(org.hamcrest.Matchers.isIn(new String[] {"OUT_OF_STOCK", "LOW", "AVAILABLE"})))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
	}

	@Test
	void ownerCanListSellableSkusWithoutOptionalUuidFilters() throws Exception {
		String token = accessToken(OWNER_EMAIL, "PLATFORM");

		mockMvc.perform(get("/api/v1/skus")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("page", "0")
				.param("size", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isNotEmpty());
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

	@Test
	void managedCatalogEditsSurviveASeedRestartAndSeedHistoryIsOneShot() {
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());
		UUID productId = jdbc.queryForObject("select id from catalog_management.product where tenant_id=? and workspace_id=? and catalog_item_id='CAT-0001'",
				UUID.class, tenant, workspace);
		jdbc.update("update catalog_management.product set name='Managed edit survives restart',updated_at=current_timestamp where id=?", productId);
		seed.importDeterministicSeed();
		assertThat(jdbc.queryForObject("select name from catalog_management.product where id=?", String.class, productId))
				.isEqualTo("Managed edit survives restart");
		assertThat(jdbc.queryForObject("select count(*) from catalog_management.seed_import_history where tenant_id=? and workspace_id=? and seed_version='v1'",
					Integer.class, tenant, workspace)).isEqualTo(1);
	}

	@Test
	void buyerPricingPreviewReturnsReal390PenDiscountAndHonorsMinimumQuantity() throws Exception {
		String owner = accessToken(OWNER_EMAIL, "PLATFORM");
		String buyer = accessToken(BUYER_EMAIL, "PORTAL");
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());
		UUID category = jdbc.queryForObject("select id from catalog_management.category where tenant_id=? and workspace_id=? limit 1", UUID.class, tenant, workspace);
		UUID brand = jdbc.queryForObject("select id from catalog_management.brand where tenant_id=? and workspace_id=? limit 1", UUID.class, tenant, workspace);
		previewProduct = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?)",
				previewProduct, tenant, workspace, "PREVIEW-" + previewProduct, "PREVIEW-" + previewProduct, "preview-" + previewProduct, "Preview cheese", "Pricing preview fixture", category, brand, "REFRIGERATED", Timestamp.from(now), Timestamp.from(now));
		jdbc.update("insert into catalog_management.product_presentation (product_id,tenant_id,workspace_id,presentation,unit_of_measure,version,updated_at) values (?,?,?,?,?,0,?)",
				previewProduct, tenant, workspace, "UNIT", "UNIT", Timestamp.from(now));
		jdbc.update("insert into catalog_management.product_visibility (product_id,tenant_id,workspace_id,buyer_visible,sales_visible,warehouse_visible,logistics_visible,version,updated_at) values (?,?,?,?,?,?,?,0,?)",
				previewProduct, tenant, workspace, true, true, true, true, Timestamp.from(now));
		jdbc.update("insert into catalog_management.product_price (id,tenant_id,workspace_id,product_id,amount,currency,valid_from,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?,0,?)",
				UUID.randomUUID(), tenant, workspace, previewProduct, new java.math.BigDecimal("390.00"), "PEN", Timestamp.valueOf("2020-01-01 00:00:00"), "PREVIEW", "Pricing preview fixture", Timestamp.from(now));

		String createBody = "{\"slug\":\"preview-promotion-" + previewProduct + "\",\"name\":\"Preview ten percent\",\"description\":\"integration\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"currency\":null,\"startsAt\":\"" + now.minusSeconds(60) + "\",\"endsAt\":null,\"minimumQuantity\":5,\"stackingPolicy\":\"EXCLUSIVE\",\"productIds\":[\"" + previewProduct + "\"],\"categoryIds\":[],\"clientAccountIds\":[],\"rules\":[],\"priority\":50}";
		MvcResult created = mockMvc.perform(post("/api/v1/catalog/promotions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + owner).header("Idempotency-Key", "preview-" + previewProduct)
				.contentType(MediaType.APPLICATION_JSON).content(createBody))
			.andExpect(status().isCreated()).andReturn();
		JsonNode promotionJson = JSON.readTree(created.getResponse().getContentAsString());
		previewPromotion = UUID.fromString(promotionJson.get("id").asText());
		MvcResult activated = mockMvc.perform(post("/api/v1/catalog/promotions/" + previewPromotion + "/activations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + owner).header(HttpHeaders.IF_MATCH, created.getResponse().getHeader(HttpHeaders.ETAG)))
			.andExpect(status().isOk()).andReturn();

		mockMvc.perform(post("/api/v1/catalog/pricing-preview").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":\"" + previewProduct + "\",\"quantity\":4}]}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].baseUnitPrice.amount").value(390.0))
			.andExpect(jsonPath("$.items[0].effectiveUnitPrice.amount").value(390.0))
			.andExpect(jsonPath("$.items[0].appliedPromotions").isEmpty());

		mockMvc.perform(post("/api/v1/catalog/pricing-preview").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":\"" + previewProduct + "\",\"quantity\":5}]}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].baseUnitPrice.amount").value(390.0))
			.andExpect(jsonPath("$.items[0].effectiveUnitPrice.amount").value(351.0))
			.andExpect(jsonPath("$.items[0].discountAmount.amount").value(39.0))
			.andExpect(jsonPath("$.items[0].lineBaseTotal.amount").value(1950.0))
			.andExpect(jsonPath("$.items[0].lineEffectiveTotal.amount").value(1755.0))
			.andExpect(jsonPath("$.items[0].appliedPromotions[0].name").value("Preview ten percent"));
	}
}
