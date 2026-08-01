package com.nexa.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP workflow fixture shared by TASK-008 and TASK-010 integration gates. */
public abstract class NexaWorkflowIntegrationSupport extends PostgresIntegrationSupport {
    protected PurchaseRequestResource createApprovedPurchaseRequest() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer).header("If-Match", etag)
                        .header("Idempotency-Key", "submit-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult inReview = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/reviews")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", submitted.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        MvcResult approved = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/approvals")
                        .header("Authorization", "Bearer " + sales)
                        .header("If-Match", inReview.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        return new PurchaseRequestResource(requestId, approved.getResponse().getHeader("ETag"), sales);
    }

    protected SalesOrderResource convert(PurchaseRequestResource request, String key) throws Exception {
        MvcResult converted = mockMvc.perform(post("/api/v1/purchase-requests/" + request.id() + "/order-conversions")
                        .header("Authorization", "Bearer " + request.salesToken())
                        .header("If-Match", request.etag()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        return new SalesOrderResource(json(converted).get("id").asText(), converted.getResponse().getHeader("ETag"), request.salesToken());
    }

    protected SalesOrderResource createSalesOrder() throws Exception {
        return convert(createApprovedPurchaseRequest(), "convert-" + UUID.randomUUID());
    }

    protected DispatchResource createReservedDispatch() throws Exception {
        SalesOrderResource pending = createSalesOrder();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/sales-orders/" + pending.id() + "/confirmations")
                        .header("Authorization", "Bearer " + pending.salesToken()).header("If-Match", pending.etag()))
                .andExpect(status().isOk()).andReturn();
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WH-" + suffix + "\",\"name\":\"Dispatch test warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        String warehouseId = json(createdWarehouse).get("id").asText();
        MvcResult createdZone = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones").header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Z-" + suffix + "\",\"name\":\"Frozen dispatch zone\",\"type\":\"FROZEN\",\"temperatureMin\":-25,\"temperatureMax\":-15}"))
                .andExpect(status().isCreated()).andReturn();
        String zoneId = json(createdZone).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + warehouse).header("Idempotency-Key", "inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"B-" + suffix + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":20,\"unit\":\"UNIT\",\"temperatureReading\":-18}"))
                .andExpect(status().isCreated());
        MvcResult reservation = mockMvc.perform(post("/api/v1/fulfillment-candidates/" + pending.id() + "/inventory-reservations")
                        .header("Authorization", "Bearer " + warehouse).header("If-Match", confirmed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "reserve-" + suffix))
                .andExpect(status().isCreated()).andReturn();
        String reservationId = json(reservation).get("id").asText();
        String logistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        MvcResult dispatch = mockMvc.perform(post("/api/v1/inventory-reservations/" + reservationId + "/dispatch-orders")
                        .header("Authorization", "Bearer " + logistics).header("If-Match", reservation.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "dispatch-create-" + suffix))
                .andExpect(status().isCreated()).andReturn();
        return new DispatchResource(json(dispatch).get("id").asText(), json(dispatch).get("dispatchNumber").asText(), dispatch.getResponse().getHeader("ETag"), logistics, reservationId, confirmed.getResponse().getHeader("ETag"), pending.id());
    }

    protected tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    public record PurchaseRequestResource(String id, String etag, String salesToken) { }
    public record SalesOrderResource(String id, String etag, String salesToken) { }
    public record DispatchResource(String id, String dispatchNumber, String etag, String logisticsToken, String reservationId, String reservationEtag, String salesOrderId) { }
}
