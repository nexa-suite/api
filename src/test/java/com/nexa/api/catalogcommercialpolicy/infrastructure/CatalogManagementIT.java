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
	private UUID buyerPriceList;
	private UUID buyerPriceListItem;
	private UUID buyerCustomerTerms;
	private UUID createdSkuId;

	@AfterEach
	void removePreviewFixture() {
		if (buyerCustomerTerms != null) tenantJdbc().update("delete from catalog_management.customer_terms where terms_id=?", buyerCustomerTerms);
		if (buyerPriceListItem != null) tenantJdbc().update("delete from catalog_management.price_list_item where item_id=?", buyerPriceListItem);
		if (buyerPriceList != null) tenantJdbc().update("delete from catalog_management.price_list where price_list_id=?", buyerPriceList);
		if (previewPromotion != null) {
			tenantJdbc().update("delete from catalog_management.promotion_product where promotion_id=?", previewPromotion);
			tenantJdbc().update("delete from catalog_management.promotion_category where promotion_id=?", previewPromotion);
			tenantJdbc().update("delete from catalog_management.promotion_client_account where promotion_id=?", previewPromotion);
			tenantJdbc().update("delete from catalog_management.promotion_rule where promotion_id=?", previewPromotion);
			tenantJdbc().update("delete from catalog_management.promotion where id=?", previewPromotion);
		}
		if (previewProduct != null) {
			tenantJdbc().update("delete from catalog_management.product_price where product_id=?", previewProduct);
			tenantJdbc().update("delete from catalog_management.product_presentation where product_id=?", previewProduct);
			tenantJdbc().update("delete from catalog_management.product_visibility where product_id=?", previewProduct);
			tenantJdbc().update("delete from catalog_management.product where id=?", previewProduct);
		}
		if (createdSkuId != null) {
			UUID tenant = UUID.fromString(tenantId());
			UUID workspace = UUID.fromString(workspaceId());
			UUID productId = tenantJdbc().query(
					"select legacy_product_id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id=?",
					rs -> rs.next() ? rs.getObject("legacy_product_id", UUID.class) : null,
					tenant, workspace, createdSkuId);
			tenantJdbc().update("delete from catalog_management.sku_price where tenant_id=? and workspace_id=? and sku_id=?",
					tenant, workspace, createdSkuId);
			tenantJdbc().update("delete from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id=?",
					tenant, workspace, createdSkuId);
			if (productId != null) {
				tenantJdbc().update("delete from catalog_management.product_price where tenant_id=? and workspace_id=? and product_id=?",
						tenant, workspace, productId);
				tenantJdbc().update("delete from catalog_management.product_presentation where tenant_id=? and workspace_id=? and product_id=?",
						tenant, workspace, productId);
				tenantJdbc().update("delete from catalog_management.product_visibility where tenant_id=? and workspace_id=? and product_id=?",
						tenant, workspace, productId);
				tenantJdbc().update("delete from catalog_management.product_asset_reference where tenant_id=? and workspace_id=? and product_id=?",
						tenant, workspace, productId);
				tenantJdbc().update("delete from catalog_management.product where tenant_id=? and workspace_id=? and id=?",
						tenant, workspace, productId);
				assertThat(tenantJdbc().queryForObject(
						"select count(*) from catalog_management.product where tenant_id=? and workspace_id=? and id=?",
						Integer.class, tenant, workspace, productId)).isZero();
			}
			assertThat(tenantJdbc().queryForObject(
					"select count(*) from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id=?",
					Integer.class, tenant, workspace, createdSkuId)).isZero();
			createdSkuId = null;
		}
	}

	@Test
	void newlyCreatedSkuCanBeReadByBuyerOnlyAfterExplicitActivation() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        var families = mockMvc.perform(get("/api/v1/product-families").param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        String familyId = null;
        for (JsonNode family : JSON.readTree(families.getResponse().getContentAsString()).get("items")) {
            if ("ACTIVE".equals(family.get("status").asText())) { familyId = family.get("id").asText(); break; }
        }
        assertThat(familyId).isNotNull();
        var created = mockMvc.perform(post("/api/v1/product-families/" + familyId + "/skus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"IT-" + UUID.randomUUID() + "\",\"presentation\":\"Synthetic unit\",\"packagingType\":\"UNIT\",\"unitOfMeasure\":\"UNIT\",\"packQuantity\":1,\"shelfLifeDays\":30,\"minimumRemainingShelfLifeDays\":0,\"lotTrackingRequired\":true,\"expiryTrackingRequired\":true,\"taxCategory\":\"STANDARD\"}"))
                .andExpect(status().isCreated()).andReturn();
        String skuId = JSON.readTree(created.getResponse().getContentAsString()).get("id").asText();
		createdSkuId = UUID.fromString(skuId);
        String catalogId = "CAT-SKU-" + skuId.replace("-", "").toUpperCase(java.util.Locale.ROOT);
        mockMvc.perform(get("/api/v1/catalog-items/" + catalogId).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/skus/" + skuId + "/prices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner).header("Idempotency-Key", "new-sku-price-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10,\"currency\":\"PEN\",\"validFrom\":\"2020-01-01T00:00:00Z\",\"sourceCode\":\"TEST\",\"sourceDescription\":\"Synthetic test price\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/skus/" + skuId + "/activations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .header(HttpHeaders.IF_MATCH, created.getResponse().getHeader("ETag")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog-items/" + catalogId).header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalogItemId").value(catalogId))
                .andExpect(jsonPath("$.brandName").isNotEmpty()).andExpect(jsonPath("$.categoryName").isNotEmpty());
    }

	@Test
	void catalogFiltersComposeBeforePaginationAndKeepTaxonomyMetadata() throws Exception {
		String token = accessToken(OWNER_EMAIL, "PLATFORM");
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        for (String kind : new String[] {"brand", "category"}) {
            mockMvc.perform(get("/api/v1/catalog-items/filter-options").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                            .param("kind", kind).param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").isNotEmpty())
                    .andExpect(jsonPath("$.items[0].name").isNotEmpty()).andExpect(jsonPath("$.size").value(1));
            mockMvc.perform(get("/api/v1/catalog-items/filter-options").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                            .param("kind", kind).param("search", "missing-" + UUID.randomUUID()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.total").value(0));
        }

		String tenant = tenantId();
		String workspace = workspaceId();
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.sellable_sku s "
				+ "left join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
				+ "left join catalog_management.brand b on b.tenant_id=f.tenant_id and b.workspace_id=f.workspace_id and b.id=f.brand_id "
				+ "left join catalog_management.category c on c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.id=f.category_id "
				+ "where s.tenant_id=? and s.workspace_id=? and (f.id is null or b.id is null or c.id is null or btrim(f.name)='' or btrim(b.name)='' or btrim(c.name)='')",
				Integer.class, UUID.fromString(tenant), UUID.fromString(workspace))).isZero();

		for (String path : new String[] {"/api/v1/catalog-items", "/api/v1/catalog/products", "/api/v1/product-families"}) {
			MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param("size", "1")).andExpect(status().isOk()).andReturn();
			JsonNode item = JSON.readTree(result.getResponse().getContentAsString()).get("items").get(0);
			String brand = item.get("brandName").asText();
			String category = item.get("categoryName").asText();
			String name = item.get(path.equals("/api/v1/catalog-items") ? "itemName" : "name").asText();
			assertThat(brand).isNotBlank();
			assertThat(category).isNotBlank();
			assertThat(name).isNotBlank();
			String queryKey = path.equals("/api/v1/catalog-items") ? "q" : "search";
			String totalPath = path.equals("/api/v1/catalog-items") ? "$.totalItems" : "$.total";
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param(queryKey, name).param("brand", brand.toUpperCase(java.util.Locale.ROOT))
					.param("category", category.toUpperCase(java.util.Locale.ROOT)).param("size", "1"))
					.andExpect(status().isOk()).andExpect(jsonPath(totalPath).value(greaterThan(0)))
					.andExpect(jsonPath("$.items[0].brandName").value(brand))
					.andExpect(jsonPath("$.items[0].categoryName").value(category));
			for (String filter : new String[] {"brand", "category"}) {
				mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.param(queryKey, name).param(filter, "missing-" + UUID.randomUUID()))
						.andExpect(status().isOk()).andExpect(jsonPath(totalPath).value(0))
						.andExpect(jsonPath("$.items").isEmpty());
			}
            for (String filter : new String[] {"brand", "category"}) {
                String value = filter.equals("brand") ? brand : category;
                mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .param(filter, strictTaxonomySubstring(filter, value)))
                        .andExpect(status().isOk()).andExpect(jsonPath(totalPath).value(0))
                        .andExpect(jsonPath("$.items").isEmpty());
            }
		}

        for (String kind : new String[] {"brand", "category"}) {
            MvcResult options = mockMvc.perform(get("/api/v1/catalog-items/filter-options")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                            .param("kind", kind).param("size", "1"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode option = JSON.readTree(options.getResponse().getContentAsString()).get("items").get(0);
            String idParameter = kind + "Id";
            mockMvc.perform(get("/api/v1/catalog-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                            .param(idParameter, option.get("id").asText()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems", greaterThan(0)));
        }
	}

    @Test
    void buyerFiltersCatalogByFamilyAndVariantOptions() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");

        MvcResult familyOptions = mockMvc.perform(get("/api/v1/catalog-items/filter-options")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                        .param("kind", "family").param("search", "QUESO GOUDA").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items[0].name").value("QUESO GOUDA"))
                .andReturn();
        JsonNode family = JSON.readTree(familyOptions.getResponse().getContentAsString()).get("items").get(0);
        String familyId = family.get("id").asText();

        MvcResult variantOptions = mockMvc.perform(get("/api/v1/catalog-items/filter-options")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                        .param("kind", "variant").param("familyId", familyId)
                        .param("search", "Cabra").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Cabra"))
                .andReturn();
        JsonNode variant = JSON.readTree(variantOptions.getResponse().getContentAsString()).get("items").get(0);

        mockMvc.perform(get("/api/v1/catalog-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer)
                        .param("familyId", familyId).param("variantId", variant.get("id").asText())
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalItems", greaterThan(0)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productFamilyId").value(familyId))
                .andExpect(jsonPath("$.items[0].productVariantName").value("Cabra"));
    }

    private String strictTaxonomySubstring(String kind, String name) {
        String table = switch (kind) {
            case "brand" -> "brand";
            case "category" -> "category";
            default -> throw new IllegalArgumentException("Unsupported catalog taxonomy kind");
        };
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        for (int length = name.length() - 1; length > 0; length--) {
            String candidate = name.substring(0, length);
            Integer exactMatches = tenantJdbc().queryForObject("select count(*) from catalog_management." + table
                            + " where tenant_id=? and workspace_id=? and lower(name)=lower(?)",
                    Integer.class, tenant, workspace, candidate);
            if (exactMatches != null && exactMatches == 0) return candidate;
        }
        throw new AssertionError("Expected a strict taxonomy substring that is not another exact name");
    }

	@Test
	void ownerReplaysVariantCreationAndRejectsDifferentPayloadForTheSameKey() throws Exception {
		String token = accessToken(OWNER_EMAIL, "PLATFORM");
		MvcResult families = mockMvc.perform(get("/api/v1/product-families")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andReturn();
		String familyId = JSON.readTree(families.getResponse().getContentAsString()).get("items").get(0).get("id").asText();
		mockMvc.perform(get("/api/v1/product-families/" + familyId + "/variants")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
		String code = "variant-" + UUID.randomUUID();
		String key = "variant-create-" + UUID.randomUUID();
		String body = "{\"code\":\"" + code + "\",\"name\":\"Replay variant\",\"description\":null}";
		String path = "/api/v1/product-families/" + familyId + "/variants";
		MvcResult first = mockMvc.perform(post(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn();
		String id = JSON.readTree(first.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
		mockMvc.perform(post(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(body.replace("\"description\":null", "\"description\":\"<null>\"")))
				.andExpect(status().isConflict());
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.product_variant where variant_code=?", Integer.class, code))
				.isEqualTo(1);
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
			.andExpect(jsonPath("$.items[0].basePrice").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[0].currentOfferPrice.amount").exists())
			.andExpect(jsonPath("$.items[0].sellableAvailability").isNumber())
			.andExpect(jsonPath("$.items[0].availabilityStatus")
					.value(org.hamcrest.Matchers.isIn(new String[] {"OUT_OF_STOCK", "LOW", "AVAILABLE"})))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"));

		String platformToken = accessToken(OWNER_EMAIL, "PLATFORM");
		mockMvc.perform(get("/api/v1/catalog-items")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
				.param("page", "0")
				.param("size", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].basePrice.amount").exists())
			.andExpect(jsonPath("$.items[0].currentOfferPrice").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void buyerCatalogResolvesThePriceListPermittedByCurrentCustomerTerms() throws Exception {
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());
		UUID account = UUID.fromString(buyerClientAccountId());
		UUID sku = tenantJdbc().queryForObject("select id from catalog_management.sellable_sku "
				+ "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0001'",
				UUID.class, tenant, workspace);
		buyerPriceList = UUID.randomUUID();
		buyerPriceListItem = UUID.randomUUID();
		buyerCustomerTerms = UUID.randomUUID();
		Timestamp startsAt = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
		Timestamp endsAt = Timestamp.from(Instant.parse("2099-12-31T00:00:00Z"));
		Timestamp createdAt = Timestamp.from(Instant.now());
		tenantJdbc().update("insert into catalog_management.price_list (price_list_id,tenant_id,workspace_id,code,name,currency,status,valid_from,valid_to,created_at,updated_at,version) "
				+ "values (?,?,?,?,?,'PEN','ACTIVE',?,?,?, ?,0)", buyerPriceList, tenant, workspace,
				"IT-" + buyerPriceList, "Buyer price list", startsAt, endsAt, createdAt, createdAt);
		tenantJdbc().update("insert into catalog_management.price_list_item (item_id,tenant_id,workspace_id,price_list_id,sku_id,unit_price,currency,valid_from,valid_to) "
				+ "values (?,?,?,?,?,?,'PEN',?,?)", buyerPriceListItem, tenant, workspace,
				buyerPriceList, sku, new java.math.BigDecimal("250.00"), startsAt, endsAt);
		tenantJdbc().update("insert into catalog_management.customer_terms (terms_id,tenant_id,workspace_id,customer_account_id,price_list_id,credit_days,currency,valid_from,valid_to,created_at,version) "
				+ "values (?,?,?,?,?,0,'PEN',?,?,?,0)", buyerCustomerTerms, tenant, workspace, account,
				buyerPriceList, startsAt, endsAt, createdAt);

		mockMvc.perform(get("/api/v1/catalog-items/CAT-0001")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(BUYER_EMAIL, "PORTAL")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentOfferPrice.amount").value("250"))
			.andExpect(jsonPath("$.currentOfferPrice.currency").value("PEN"))
			.andExpect(jsonPath("$.pricingAsOf").isNotEmpty());
	}

	@Test
	void inactiveCustomerAccountCannotReadBuyerCatalog() throws Exception {
		String buyer = accessToken(BUYER_EMAIL, "PORTAL");
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());
		UUID account = UUID.fromString(buyerClientAccountId());
		try {
			tenantJdbc().update("update sales.client_account set status='INACTIVE',version=version+1 where tenant_id=? and workspace_id=? and id=?",
					tenant, workspace, account);
			mockMvc.perform(get("/api/v1/catalog-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer))
					.andExpect(status().isForbidden());
			mockMvc.perform(get("/api/v1/catalog-items/CAT-0001").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer))
					.andExpect(status().isForbidden());
		} finally {
			tenantJdbc().update("update sales.client_account set status='ACTIVE',version=version+1 where tenant_id=? and workspace_id=? and id=?",
					tenant, workspace, account);
		}
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
		UUID productId = tenantJdbc().queryForObject("select id from catalog_management.product where tenant_id=? and workspace_id=? and catalog_item_id='CAT-0001'",
				UUID.class, tenant, workspace);
		tenantJdbc().update("update catalog_management.product set name='Managed edit survives restart',updated_at=current_timestamp where id=?", productId);
		seed.importDeterministicSeed();
		assertThat(tenantJdbc().queryForObject("select name from catalog_management.product where id=?", String.class, productId))
				.isEqualTo("Managed edit survives restart");
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.seed_import_history where tenant_id=? and workspace_id=? and seed_version='v2'",
					Integer.class, tenant, workspace)).isEqualTo(1);
	}

	@Test
	void persistenceSeedContainsCanonicalItemsAndKeepsProvisionalReferencesOutOfBuyerVisibility() {
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());

		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.product where tenant_id=? and workspace_id=? and catalog_item_id ~ '^CAT-[0-9]{4}$'",
				Integer.class, tenant, workspace)).isEqualTo(102);
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.product_visibility pv "
				+ "join catalog_management.product p on p.id=pv.product_id and p.tenant_id=pv.tenant_id and p.workspace_id=pv.workspace_id "
				+ "where pv.tenant_id=? and pv.workspace_id=? and p.catalog_item_id ~ '^CAT-[0-9]{4}$' and pv.buyer_visible=false",
				Integer.class, tenant, workspace)).isEqualTo(52);
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.product_price pp "
				+ "join catalog_management.product p on p.id=pp.product_id and p.tenant_id=pp.tenant_id and p.workspace_id=pp.workspace_id "
				+ "where pp.tenant_id=? and pp.workspace_id=? and p.catalog_item_id ~ '^CAT-[0-9]{4}$' and pp.source_code='PROVISIONAL_REFERENCE'",
				Integer.class, tenant, workspace)).isEqualTo(52);
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and legacy_catalog_item_id ~ '^CAT-[0-9]{4}$'",
				Integer.class, tenant, workspace)).isEqualTo(102);
		assertThat(tenantJdbc().queryForObject("select pf.family_code from catalog_management.sellable_sku sku "
				+ "join catalog_management.product_family pf on pf.id=sku.family_id and pf.tenant_id=sku.tenant_id and pf.workspace_id=sku.workspace_id "
				+ "where sku.tenant_id=? and sku.workspace_id=? and sku.legacy_catalog_item_id='CAT-0014'",
				String.class, tenant, workspace)).isEqualTo("FAM-GOUDA");
		assertThat(tenantJdbc().queryForObject("select count(*) from catalog_management.seed_import_history where tenant_id=? and workspace_id=? and seed_version='v2'",
				Integer.class, tenant, workspace)).isEqualTo(1);
	}

	@Test
	void buyerPricingPreviewReturnsReal390PenDiscountAndHonorsMinimumQuantity() throws Exception {
		String owner = accessToken(OWNER_EMAIL, "PLATFORM");
		String buyer = accessToken(BUYER_EMAIL, "PORTAL");
		UUID tenant = UUID.fromString(tenantId());
		UUID workspace = UUID.fromString(workspaceId());
		UUID category = tenantJdbc().queryForObject("select id from catalog_management.category where tenant_id=? and workspace_id=? limit 1", UUID.class, tenant, workspace);
		UUID brand = tenantJdbc().queryForObject("select id from catalog_management.brand where tenant_id=? and workspace_id=? limit 1", UUID.class, tenant, workspace);
		previewProduct = UUID.randomUUID();
		Instant now = Instant.now();
		tenantJdbc().update("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?)",
				previewProduct, tenant, workspace, "PREVIEW-" + previewProduct, "PREVIEW-" + previewProduct, "preview-" + previewProduct, "Preview cheese", "Pricing preview fixture", category, brand, "REFRIGERATED", Timestamp.from(now), Timestamp.from(now));
		tenantJdbc().update("insert into catalog_management.product_presentation (product_id,tenant_id,workspace_id,presentation,unit_of_measure,version,updated_at) values (?,?,?,?,?,0,?)",
				previewProduct, tenant, workspace, "UNIT", "UNIT", Timestamp.from(now));
		tenantJdbc().update("insert into catalog_management.product_visibility (product_id,tenant_id,workspace_id,buyer_visible,sales_visible,warehouse_visible,logistics_visible,version,updated_at) values (?,?,?,?,?,?,?,0,?)",
				previewProduct, tenant, workspace, true, true, true, true, Timestamp.from(now));
		tenantJdbc().update("insert into catalog_management.product_price (id,tenant_id,workspace_id,product_id,amount,currency,valid_from,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?,0,?)",
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
