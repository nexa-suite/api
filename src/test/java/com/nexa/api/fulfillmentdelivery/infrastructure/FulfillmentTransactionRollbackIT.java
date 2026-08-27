package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controlled transaction failure seam after physical allocation planning. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FulfillmentTransactionRollbackIT extends NexaWorkflowIntegrationSupport {

    @MockitoBean
    FulfillmentPersistencePort fulfillments;

    @Test
    void failureAfterPhysicalAllocationRollsBackAllocationBackingLotReservationAndOutbox() throws Exception {
        SalesOrderResource order = createConfirmedDirectOrder();
        UUID orderId = UUID.fromString(order.id());
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        UUID backingId = jdbc.queryForObject(
                "select b.id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=?",
                UUID.class, tenant, workspace, orderId);
        String backingStatus = jdbc.queryForObject("select status from warehouse.inventory_backing where id=?", String.class, backingId);
        long backingVersion = jdbc.queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId);
        BigDecimal reservedBefore = jdbc.queryForObject(
                "select coalesce(sum(reserved_quantity),0) from warehouse.inventory_lot where tenant_id=? and workspace_id=?",
                BigDecimal.class, tenant, workspace);
        int outboxBefore = jdbc.queryForObject(
                "select count(*) from integration.outbox_event where tenant_id=? and workspace_id=? and event_type='PhysicalAllocationCreated.v1'",
                Integer.class, tenant, workspace);

        doThrow(new IllegalStateException("failure-injection-after-physical-allocation"))
                .when(fulfillments).createAllocated(any(FulfillmentPersistencePort.CreateRequest.class));

        MvcResult failed = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.etag())
                        .header("Idempotency-Key", "rollback-physical-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.physical_allocation where tenant_id=? and workspace_id=? and sales_order_id=?",
                Integer.class, tenant, workspace, orderId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=?",
                Integer.class, tenant, workspace, orderId)).isZero();
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_backing where id=?", String.class, backingId))
                .isEqualTo(backingStatus);
        assertThat(jdbc.queryForObject("select version from warehouse.inventory_backing where id=?", Long.class, backingId))
                .isEqualTo(backingVersion);
        assertThat(jdbc.queryForObject("select coalesce(sum(reserved_quantity),0) from warehouse.inventory_lot where tenant_id=? and workspace_id=?",
                BigDecimal.class, tenant, workspace)).isEqualByComparingTo(reservedBefore);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where tenant_id=? and workspace_id=? and event_type='PhysicalAllocationCreated.v1'",
                Integer.class, tenant, workspace)).isEqualTo(outboxBefore);
    }

    private SalesOrderResource createConfirmedDirectOrder() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", "rollback-physical-order-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Rollback delivery\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return new SalesOrderResource(json(created).get("id").asText(), created.getResponse().getHeader("ETag"), sales);
    }
}
