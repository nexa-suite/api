package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestCompositionFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import com.nexa.api.salescommitment.application.policy.BuyerDeliveryLeadBusinessPolicy;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
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
import tools.jackson.databind.JsonNode;

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
@TestPropertySource(properties = {
        "nexa.tenant-business.purchase-request.enabled=false",
        "nexa.tenant-business.fulfillment-delivery.enabled=false"
})
@Import(CloudBuyerCheckoutHttpIT.CheckoutComposition.class)
class CloudBuyerCheckoutHttpIT extends NexaWorkflowIntegrationSupport {
    private static final String DRAFTS = "/api/v1/buyer/purchase-request-drafts";
    private static final String ADDRESSES = "/api/v1/client-accounts/{accountId}/addresses";

    @Test
    void buyerAndOperationsCompleteTheSameOrderThroughTheCloudComposition() throws Exception {
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
        String warehouse = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String delivery = dispatchOrder(order, converted, warehouse);
        String logistics = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        acceptDelivery(delivery, sku, logistics, buyer);
        assertThat(tenantJdbc().queryForObject("""
                select o.status from logistics.delivery d
                join logistics.fulfillment f on f.id=d.fulfillment_id and f.tenant_id=d.tenant_id
                    and f.workspace_id=d.workspace_id
                join sales.sales_order o on o.id=f.sales_order_id and o.tenant_id=f.tenant_id
                    and o.workspace_id=f.workspace_id
                where d.id=? and o.id=? and o.source_purchase_request_id=?
                """, String.class, UUID.fromString(delivery), order, id)).isEqualTo("COMPLETED");
    }

    @Test
    void sellerReadsAnAssignedCustomerOfferAndConfirmsTheDirectOrder() throws Exception {
        ensureCommercialInventory();
        String account = buyerClientAccountId();
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=? where id=?",
                UUID.fromString(membershipId(SALES_EMAIL)), UUID.fromString(account));
        String sales = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String offer = "/api/v1/client-accounts/{id}/catalog-offers/CAT-0002";
        String direct = "/api/v1/direct-orders";
        mockMvc.perform(get(offer, account).param("quantity", "1").header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientAccountId").value(account))
                .andExpect(jsonPath("$.unitPrice.amount").isNumber());
        mockMvc.perform(get(offer, UUID.randomUUID()).header("Authorization", sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(offer, account).header("Authorization", buyer)).andExpect(status().isForbidden());
        mockMvc.perform(get(direct + "/delivery-policy").header("Authorization", sales)).andExpect(status().isOk());
        mockMvc.perform(get(direct + "/delivery-policy").header("Authorization", buyer)).andExpect(status().isForbidden());
        String date = new BuyerDeliveryLeadBusinessPolicy().minimumAllowedDeliveryDate(Clock.systemUTC()).toString();
        String body = """
                {"clientAccountId":"%s","priority":"NORMAL","requestedDeliveryDate":"%s",
                  "deliveryProfileSnapshot":"Direct order delivery","paymentOption":"IMMEDIATE",
                  "lines":[{"catalogItemId":"CAT-0002","quantity":1,"unit":"UNIT"}]}
                """.formatted(account, date);
        String key = "cloud-direct-" + uuid();
        String orderId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mockMvc.perform(post(direct).header("Authorization", sales).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.originType").value("DIRECT_ORDER")).andReturn();
            String current = json(result).path("id").asText();
            if (orderId != null) assertThat(current).isEqualTo(orderId);
            orderId = current;
        }
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.sales_order where id=?",
                Integer.class, UUID.fromString(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where id=?",
                Integer.class, UUID.fromString(orderId))).isZero();
    }

    private String dispatchOrder(UUID order, MvcResult orderView, String warehouse) throws Exception {
        var fulfill = post("/api/v1/sales-orders/{id}/fulfillments", order);
        var created = command(fulfill, warehouse, orderView, null, 201);
        String fulfillment = "/api/v1/fulfillments/" + json(created).path("id").asText();
        String lineId = json(created).path("lines").get(0).path("id").asText();
        var started = command(post(fulfillment + "/picking-starts"), warehouse, created, null, 200);
        var allocation = readAllocation(fulfillment, warehouse);
        JsonNode line = allocation.path("lines").get(0);
        assertThat(line.path("quantity").decimalValue()).isEqualByComparingTo("1");
        String picking = """
                {"allocationVersion":%d,"lines":[{"fulfillmentLineId":"%s","skuId":"%s",
                  "quantity":1,"unit":"UNIT","physicalAllocationLineId":"%s","lotId":"%s","warehouseId":"%s"}]}
                """.formatted(allocation.path("version").asLong(), lineId, line.path("skuId").asText(),
                line.path("physicalAllocationLineId").asText(), line.path("lotId").asText(),
                line.path("warehouseId").asText());
        var current = command(post(fulfillment + "/picking-confirmations"), warehouse, started, picking, 200);
        for (String step : new String[]{"/packing", "/staging", "/ready-for-dispatch"}) {
            current = command(post(fulfillment + step), warehouse, current, null, 200);
        }
        allocation = readAllocation(fulfillment, warehouse);
        String driverMembership = membershipId(LOGISTICS_EMAIL);
        grantWarehouseAccess(allocation.path("lines").get(0).path("warehouseId").asText(), driverMembership);
        String logistics = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        startDriverWorkday(logistics);
        String assignment = """
                {"responsibleMembershipId":"%s","physicalAllocationId":"%s","physicalAllocationVersion":%d}
                """.formatted(driverMembership, allocation.path("allocationId").asText(),
                allocation.path("version").asLong());
        var assigned = command(post(fulfillment + "/driver-assignments"), logistics, current, assignment, 200);
        allocation = readAllocation(fulfillment, warehouse);
        line = allocation.path("lines").get(0);
        String check = """
                {"physicalAllocationId":"%s","physicalAllocationVersion":%d,"observations":[
                  {"physicalAllocationLineId":"%s","observedLotId":"%s","observedQuantity":%s}]}
                """.formatted(allocation.path("allocationId").asText(), allocation.path("version").asLong(),
                line.path("physicalAllocationLineId").asText(), line.path("lotId").asText(),
                line.path("remainingQuantity").decimalValue().toPlainString());
        var outgoing = command(post(fulfillment + "/outgoing-checks"), warehouse, assigned, check, 201);
        String dispatch = """
                {"physicalAllocationId":"%s","physicalAllocationVersion":%d,"driverAssignmentId":"%s",
                  "driverAssignmentVersion":%d,"outgoingGoodsCheckId":"%s"}
                """.formatted(allocation.path("allocationId").asText(), allocation.path("version").asLong(),
                json(assigned).path("id").asText(), json(assigned).path("fulfillmentVersion").asLong(),
                json(outgoing).path("id").asText());
        var dispatched = command(post(fulfillment + "/dispatches"), logistics, assigned, dispatch, 200);
        return json(dispatched).path("deliveryId").asText();
    }

    private JsonNode readAllocation(String fulfillment, String warehouse) throws Exception {
        return json(mockMvc.perform(get(fulfillment + "/physical-allocation").header("Authorization", warehouse))
                .andExpect(status().isOk()).andReturn());
    }

    private void startDriverWorkday(String logistics) throws Exception {
        var workday = command(post("/api/v1/driver/workdays"), logistics, null,
                """
                {"locationAvailable":false}
                """, 200);
        String workdayId = json(workday).path("id").asText();
        command(post("/api/v1/driver/workdays/{id}/location-availability", workdayId), logistics, workday,
                """
                {"locationAvailable":true}
                """, 200);
        mockMvc.perform(get("/api/v1/driver/location").header("Authorization", logistics))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_LOCATION_UNAVAILABLE"));
    }

    private void acceptDelivery(String delivery, String sku, String logistics, String buyer) throws Exception {
        String driverPath = "/api/v1/driver/deliveries/" + delivery;
        var driverDelivery = mockMvc.perform(get(driverPath).header("Authorization", logistics))
                .andExpect(status().isOk()).andReturn();
        String deliveryPath = "/api/v1/deliveries/" + delivery;
        var transit = command(post(deliveryPath + "/transit-starts"), logistics, driverDelivery, null, 200);
        var attempt = command(post(driverPath + "/attempts"), logistics, transit, "{}", 201);
        String attemptId = json(attempt).path("attempt").path("id").asText();
        String lineId = json(driverDelivery).path("outcomeLines").get(0).path("fulfillmentLineId").asText();
        String outcome = """
                {"outcome":"DELIVERED","lines":[{"fulfillmentLineId":"%s","skuId":"%s","unit":"UNIT",
                  "attemptedQuantity":1,"deliveredQuantity":1,"rejectedQuantity":0,"cancelledQuantity":0}]}
                """.formatted(lineId, sku);
        var delivered = command(post(driverPath + "/attempts/" + attemptId + "/outcomes"), logistics,
                attempt, outcome, 200);
        assertThat(json(delivered).path("delivery").path("status").asText()).isEqualTo("DELIVERED");
        var handoff = command(post(deliveryPath + "/handoff-tokens"), logistics, null,
                """
                {"attemptId":"%s"}
                """.formatted(attemptId), 201);
        String key = "cloud-receipt-" + uuid();
        String receipt = """
                {"token":"%s"}
                """.formatted(json(handoff).path("token").asText());
        var accepted = mockMvc.perform(post("/api/v1/delivery-handoff/receipts").header("Authorization", buyer)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.deliveryId").value(delivery))
                .andExpect(jsonPath("$.podStatus").value("SEALED"))
                .andExpect(jsonPath("$.salesOrderStatus").value("COMPLETED")).andReturn();
        mockMvc.perform(post("/api/v1/delivery-handoff/receipts").header("Authorization", buyer)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.receiptId").value(json(accepted).path("receiptId").asText()));
    }

    private MvcResult command(MockHttpServletRequestBuilder request, String token, MvcResult previous,
                              String body, int expectedStatus) throws Exception {
        request.header("Authorization", token).header("X-Nexa-Client", "NATIVE")
                .header("Idempotency-Key", "cloud-step-" + uuid());
        if (previous != null) request.header("If-Match", previous.getResponse().getHeader("ETag"));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
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
        CloudMobileFulfillmentConfiguration.Route cloudFulfillmentForHttpTest(TenantBusinessDatabaseRouter router,
                TenantFulfillmentDeliveryCompositionProviderAdapter compositions,
                WarehouseObjectAccess warehouses, WorkforceDirectory workforce) {
            return new CloudMobileFulfillmentConfiguration().cloudMobileFulfillmentRoute(
                    router, compositions, warehouses, workforce);
        }

        @Bean
        TenantSalesCommitmentCompositionProvider cloudSalesProviderForCheckoutTest(AutowireCapableBeanFactory factory) {
            return factory.createBean(TenantSalesCommitmentCompositionProviderAdapter.class);
        }

        @Bean
        TenantCatalogCommercialPolicyRequestPort.CustomerOfferReader cloudCustomerOfferForHttpTest(
                TenantBusinessDatabaseRouter router, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantCatalogAvailabilityAdapterFactory availability,
                TenantCatalogCommercialPolicyRequestCompositionFactory compositions, RegionalCurrencyQuery currency) {
            return new TenantBoundCatalogCommercialPolicyConfiguration().cloudCustomerOfferReader(router, accounts,
                    skus, availability, compositions, currency);
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
