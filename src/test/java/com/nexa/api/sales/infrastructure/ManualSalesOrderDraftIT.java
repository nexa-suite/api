package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ManualSalesOrderDraftIT extends PostgresIntegrationSupport {
    @Test
    void manualOrderDraftPersistsEachStepUsesIfMatchAndReplaysFinalSubmission() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        String clientId = buyerClientAccountId();
        String skuId = jdbc.queryForObject("select s.id::text from catalog_management.sellable_sku s "
                + "where s.tenant_id=? and s.workspace_id=? and s.legacy_catalog_item_id='CAT-0002' "
                + "and s.status='ACTIVE' and s.visible and exists (select 1 from warehouse.inventory_lot l "
                + "where l.tenant_id=s.tenant_id and l.workspace_id=s.workspace_id and l.sku_id=s.id "
                + "and l.status='AVAILABLE' and l.expiration_date >= current_date and l.stock_quantity-l.reserved_quantity >= 1) limit 1",
                String.class, java.util.UUID.fromString(tenantId()), java.util.UUID.fromString(workspaceId()));

        MvcResult address = mockMvc.perform(post("/api/v1/client-accounts/" + clientId + "/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Manual draft dock\",\"defaultAddress\":false,\"address\":{"
                                + "\"addressType\":\"STREET\",\"line\":\"Av. Lima 123\",\"reference\":\"Dock 2\","
                                + "\"countryCode\":\"PE\",\"departmentCode\":\"15\",\"provinceCode\":\"1501\",\"districtCode\":\"150101\","
                                + "\"recipientName\":\"Recepción\",\"recipientPhone\":\"+51999999999\",\"roadType\":\"STREET\","
                                + "\"streetName\":\"Av. Lima\",\"streetNumber\":\"123\",\"interior\":\"2\",\"postalCode\":\"15074\","
                                + "\"receivingInstructions\":\"Ingresar por recepción\",\"receivingHours\":\"08:00-16:00\","
                                + "\"latitude\":-12.0464,\"longitude\":-77.0428,\"source\":\"MANUAL\"}}"))
                .andExpect(status().isCreated())
                .andReturn();
        String addressId = json(address).get("id").asText();

        String draftKey = "manual-draft-" + uuid();
        MvcResult created = mockMvc.perform(post("/api/v1/sales-orders/manual-drafts")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", draftKey))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String draftId = json(created).get("id").asText();

        mockMvc.perform(post("/api/v1/sales-orders/manual-drafts")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", draftKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(draftId));

        MvcResult client = mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/client")
                        .header("Authorization", "Bearer " + token).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + clientId + "\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"priority\":\"HIGH\",\"paymentPreference\":\"CASH\",\"currency\":\"PEN\",\"notes\":\"Manual flow\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLIENT_COMPLETE"))
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();

        mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/items")
                        .header("Authorization", "Bearer " + token).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"skuId\":\"" + skuId + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isPreconditionFailed());

        MvcResult items = mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/items")
                        .header("Authorization", "Bearer " + token).header("If-Match", client.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"skuId\":\"" + skuId + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ITEMS_COMPLETE"))
                .andReturn();

        MvcResult delivery = mockMvc.perform(put("/api/v1/sales-orders/manual-drafts/" + draftId + "/delivery")
                        .header("Authorization", "Bearer " + token).header("If-Match", items.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"deliveryNotes\":\"Dock 2\",\"routeProvider\":\"LOCAL_ESTIMATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_TO_CREATE"))
                .andExpect(jsonPath("$.delivery.warehouseId").isNotEmpty())
                .andReturn();

        mockMvc.perform(get("/api/v1/sales-orders/manual-drafts/" + draftId + "/review")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyToCreate").value(true));

        String submitKey = "manual-submit-" + uuid();
        MvcResult submitted = mockMvc.perform(post("/api/v1/sales-orders/manual-drafts/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", delivery.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String orderId = json(submitted).get("id").asText();
        String orderNumber = json(submitted).get("number").asText();

        mockMvc.perform(get("/api/v1/sales-orders?search=" + orderNumber)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(orderId));

        mockMvc.perform(post("/api/v1/sales-orders/manual-drafts/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", delivery.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(orderId));
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }
}
