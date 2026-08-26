package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestDraftIT extends PostgresIntegrationSupport {
    @Test
    void replacingLinesInvalidatesSnapshotsAndBlocksSubmissionUntilRouteIsRecalculated() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String clientId = buyerClientAccountId();
        List<String> skuIds = jdbc.query("""
                select s.id::text
                from catalog_management.sellable_sku s
                join warehouse.inventory_lot l on l.tenant_id=s.tenant_id and l.workspace_id=s.workspace_id and l.sku_id=s.id
                where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible
                  and l.status='AVAILABLE' and l.expiration_date >= current_date
                  and l.stock_quantity-l.reserved_quantity >= 1
                  and l.warehouse_id = (
                      select l2.warehouse_id
                      from warehouse.inventory_lot l2
                      where l2.tenant_id=s.tenant_id and l2.workspace_id=s.workspace_id
                        and l2.status='AVAILABLE' and l2.expiration_date >= current_date
                        and l2.stock_quantity-l2.reserved_quantity >= 1
                      group by l2.warehouse_id
                      order by count(distinct l2.sku_id) desc, l2.warehouse_id
                      limit 1
                  )
                group by s.id, s.sku_code
                order by s.sku_code
                limit 2
                """, (rs, n) -> rs.getString(1), UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        assertThat(skuIds).as("two serviceable SKUs in one warehouse").hasSize(2);

        MvcResult address = mockMvc.perform(post("/api/v1/client-accounts/" + clientId + "/addresses")
                        .header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Canonical draft " + uuid().substring(0, 8) + "\",\"defaultAddress\":false,\"address\":{" +
                                "\"addressType\":\"STREET\",\"line\":\"Av. Lima 123\",\"reference\":\"Draft dock\"," +
                                "\"countryCode\":\"PE\",\"departmentCode\":\"15\",\"provinceCode\":\"1501\",\"districtCode\":\"150101\"," +
                                "\"recipientName\":\"Recepción\",\"recipientPhone\":\"+51999999999\",\"roadType\":\"STREET\"," +
                                "\"streetName\":\"Av. Lima\",\"streetNumber\":\"123\",\"interior\":\"2\",\"postalCode\":\"15074\"," +
                                "\"receivingInstructions\":\"Ingresar por recepción\",\"receivingHours\":\"08:00-16:00\"," +
                                "\"latitude\":-12.0464,\"longitude\":-77.0428,\"source\":\"MANUAL\"}}"))
                .andExpect(status().isCreated()).andReturn();
        String addressId = json(address).get("id").asText();

        MvcResult created = mockMvc.perform(post("/api/v1/buyer/purchase-request-drafts")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + clientId + "\",\"requestedDeliveryDate\":\"2099-12-31\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andReturn();
        String draftId = json(created).get("id").asText();

        MvcResult lines = mockMvc.perform(put("/api/v1/buyer/purchase-request-drafts/" + draftId + "/lines")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"skuId\":\"" + skuIds.get(0) + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andReturn();
        MvcResult destination = mockMvc.perform(put("/api/v1/buyer/purchase-request-drafts/" + draftId + "/destination")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", lines.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressId\":\"" + addressId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult route = mockMvc.perform(post("/api/v1/buyer/purchase-request-drafts/" + draftId + "/route-previews")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", destination.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"LOCAL_ESTIMATE\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult preferences = mockMvc.perform(put("/api/v1/buyer/purchase-request-drafts/" + draftId + "/preferences")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", route.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentPreference\":\"CASH\",\"requestedDeliveryDate\":\"2099-12-31\"}"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get("/api/v1/buyer/purchase-request-drafts/" + draftId + "/review")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyToSubmit").value(true));

        MvcResult replaced = mockMvc.perform(put("/api/v1/buyer/purchase-request-drafts/" + draftId + "/lines")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", preferences.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"skuId\":\"" + skuIds.get(1) + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DESTINATION_COMPLETE"))
                .andExpect(jsonPath("$.route").doesNotExist()).andExpect(jsonPath("$.warehouseSelection").doesNotExist())
                .andReturn();
        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request_draft_route where draft_id=?", Integer.class, UUID.fromString(draftId))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request_draft_warehouse_selection where draft_id=?", Integer.class, UUID.fromString(draftId))).isZero();
        mockMvc.perform(get("/api/v1/buyer/purchase-request-drafts/" + draftId + "/review")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.routeValidated").value(false))
                .andExpect(jsonPath("$.readyToSubmit").value(false));
        mockMvc.perform(post("/api/v1/buyer/purchase-request-drafts/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", replaced.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "blocked-submit-" + uuid()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request where id=?", Integer.class, UUID.fromString(draftId))).isZero();

        MvcResult recalculated = mockMvc.perform(post("/api/v1/buyer/purchase-request-drafts/" + draftId + "/route-previews")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", replaced.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"LOCAL_ESTIMATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_TO_SUBMIT"))
                .andReturn();
        MvcResult submitted = mockMvc.perform(post("/api/v1/buyer/purchase-request-drafts/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", recalculated.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "valid-submit-" + uuid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        assertThat(jdbc.queryForObject("select count(*) from sales.purchase_request where id=?", Integer.class, UUID.fromString(draftId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=?",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(draftId))).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from sales.commercial_commitment_line c join sales.commercial_commitment h on h.id=c.commitment_id where h.purchase_request_id=?",
                Integer.class, UUID.fromString(draftId))).isEqualTo(1);
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }
}
