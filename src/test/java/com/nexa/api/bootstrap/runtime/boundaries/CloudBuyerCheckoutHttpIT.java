package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.salescommitment.application.policy.BuyerDeliveryLeadBusinessPolicy;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the cloud checkout composition with isolated PostgreSQL business data. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.tenant-business.purchase-request.enabled=false")
@Import(CloudBuyerCheckoutHttpIT.CheckoutComposition.class)
class CloudBuyerCheckoutHttpIT extends NexaWorkflowIntegrationSupport {
    private static final String DRAFTS = "/api/v1/buyer/purchase-request-drafts";
    private static final String ADDRESSES = "/api/v1/client-accounts/{accountId}/addresses";

    @Test
    void buyerSubmitsAndSalesConvertsTheSameScopedRequest() throws Exception {
        ensureCommercialInventory();
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String account = buyerClientAccountId();
        String date = new BuyerDeliveryLeadBusinessPolicy().minimumAllowedDeliveryDate(Clock.systemUTC()).toString();
        var product = mockMvc.perform(get("/api/v1/catalog-items/CAT-0002").header("Authorization", buyer))
                .andExpect(status().isOk()).andReturn();
        String sku = json(product).path("sellableSkuId").asText();

        var address = mockMvc.perform(post(ADDRESSES, account).header("Authorization", buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"label":"Test delivery dock","defaultAddress":false,"address":{
                                  "addressType":"STREET","line":"Av. Synthetic 123","countryCode":"PE",
                                  "departmentCode":"15","provinceCode":"1501","districtCode":"150101",
                                  "recipientName":"Test Buyer","recipientPhone":"+51999999999",
                                  "roadType":"STREET","streetName":"Av. Synthetic","streetNumber":"123",
                                  "latitude":-12.0464,"longitude":-77.0428,"source":"MANUAL"}}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String addressId = json(address).path("id").asText();
        mockMvc.perform(get(ADDRESSES, account).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + addressId + "')]").isNotEmpty());
        mockMvc.perform(get(ADDRESSES, UUID.randomUUID()).header("Authorization", buyer))
                .andExpect(status().isNotFound());

        var created = mockMvc.perform(post(DRAFTS).header("Authorization", buyer).header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientAccountId":"%s","requestedDeliveryDate":"%s"}
                                """.formatted(account, date)))
                .andExpect(status().isCreated()).andReturn();
        String draftId = json(created).path("id").asText();
        String draft = DRAFTS + "/" + draftId;
        var lines = changeDraft(put(draft + "/lines"), buyer, created,
                """
                {"lines":[{"skuId":"%s","quantity":1,"unit":"UNIT"}]}
                """.formatted(sku));
        var destination = changeDraft(put(draft + "/destination"), buyer, lines,
                """
                {"addressId":"%s"}
                """.formatted(addressId));
        var route = changeDraft(post(draft + "/route-previews"), buyer, destination,
                """
                {"provider":"LOCAL_ESTIMATE"}
                """);
        var preferences = changeDraft(put(draft + "/preferences"), buyer, route,
                """
                {"paymentPreference":"CASH","requestedDeliveryDate":"%s"}
                """.formatted(date));
        mockMvc.perform(get(draft + "/review").header("Authorization", buyer).header("X-Nexa-Client", "NATIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyToSubmit").value(true));
        mockMvc.perform(post(draft + "/submissions")
                        .header("Authorization", buyer).header("X-Nexa-Client", "NATIVE")
                        .header("If-Match", preferences.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "cloud-checkout-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(draftId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        UUID id = UUID.fromString(draftId);
        assertThat(tenantJdbc().queryForObject(
                "select status from sales.purchase_request where id=?", String.class, id)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from sales.purchase_request where id=?", Integer.class, id)).isZero();
        mockMvc.perform(get(draft)).andExpect(status().isUnauthorized());

        String request = "/api/v1/purchase-requests/" + draftId;
        String sales = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        var current = mockMvc.perform(get(request).header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();
        mockMvc.perform(post(request + "/reviews").header("Authorization", buyer)
                        .header("If-Match", current.getResponse().getHeader("ETag")))
                .andExpect(status().isForbidden());
        var reviewed = mockMvc.perform(post(request + "/reviews").header("Authorization", sales)
                        .header("If-Match", current.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        var approved = mockMvc.perform(post(request + "/approvals").header("Authorization", sales)
                        .header("If-Match", reviewed.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        var converted = mockMvc.perform(post(request + "/order-conversions").header("Authorization", sales)
                        .header("If-Match", approved.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "cloud-convert-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED")).andReturn();
        UUID order = UUID.fromString(json(converted).path("id").asText());
        assertThat(tenantJdbc().queryForObject(
                "select source_purchase_request_id from sales.sales_order where id=?", UUID.class, order)).isEqualTo(id);
        assertThat(jdbc.queryForObject(
                "select count(*) from sales.sales_order where id=?", Integer.class, order)).isZero();
    }

    private MvcResult changeDraft(MockHttpServletRequestBuilder request, String buyer, MvcResult previous,
                                 String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", buyer).header("X-Nexa-Client", "NATIVE")
                        .header("If-Match", previous.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CheckoutComposition {
        @Bean
        TenantSalesCommitmentCompositionProvider cloudSalesProviderForCheckoutTest(AutowireCapableBeanFactory factory) {
            return factory.createBean(TenantSalesCommitmentCompositionProviderAdapter.class);
        }

        @Bean
        CloudBuyerCheckoutConfiguration.Routes cloudCheckoutForHttpTest(TenantBusinessDatabaseRouter router,
                TenantBusinessDatabaseAuthority authority, OperationalSettingsAccess settings,
                TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory policyWriter,
                TenantSalesCommitmentCompositionProvider sales, TenantCustomerRelationshipsCompositionFactory accounts,
                BuyerMembershipDirectory buyers, ActiveSalesMembershipDirectory salesMemberships,
                TenantBusinessTraceabilityBindingsFactory traceability) {
            return new CloudBuyerCheckoutConfiguration().cloudBuyerCheckoutRoutes(router, authority, settings,
                    policyWriter, sales, accounts, buyers, salesMemberships, traceability);
        }
    }
}
