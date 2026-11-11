package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.port.PickingWorkRead;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.InventoryLotsRead;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the cloud read composition over HTTP with the local isolated Tenant fixture.
 * Cloud profile selection is covered separately by MobileOperationsReadConfigurationTests. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.warehouse-operations.enabled=false",
        "nexa.tenant-business.fulfillment-delivery.enabled=false"
})
@Import(CloudMobileOperationsHttpIT.ReadComposition.class)
class CloudMobileOperationsHttpIT extends PostgresIntegrationSupport {
    @Test
    void warehouseReadsAreAuthenticatedScopedAndCommandsRemainUnavailable() throws Exception {
        String warehouse = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        for (String path : new String[]{"/api/v1/inventory/lots", "/api/v1/fulfillments"}) {
            mockMvc.perform(get(path).param("page", "0").param("size", "5")
                            .header("Authorization", warehouse))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(5));
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", buyer)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/inventory/lots/{lotId}/blocks", UUID.randomUUID())
                        .header("Authorization", warehouse).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/v1/fulfillments/{id}/picking-starts", UUID.randomUUID())
                        .header("Authorization", warehouse).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void inventoryListsGrantedLotButNotLotInOtherWarehouse() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID grantedWarehouse = UUID.randomUUID();
        UUID hiddenWarehouse = UUID.randomUUID();
        UUID grantedLot = UUID.randomUUID();
        UUID hiddenLot = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var tenantJdbc = tenantJdbc();
        var sku = tenantJdbc.query("select id,coalesce(nullif(legacy_catalog_item_id,''),sku_code) item_id,unit_of_measure "
                        + "from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and status='ACTIVE' order by sku_code limit 1",
                (rs, row) -> new Sku(rs.getObject("id", UUID.class), rs.getString("item_id"), rs.getString("unit_of_measure")),
                tenant, workspace).stream().findFirst().orElseThrow();
        seedLot(tenantJdbc, tenant, workspace, grantedWarehouse, grantedLot, "G" + suffix, sku);
        seedLot(tenantJdbc, tenant, workspace, hiddenWarehouse, hiddenLot, "H" + suffix, sku);
        jdbc.update("insert into tenant_management.warehouse_access_grant "
                        + "(tenant_id,workspace_id,membership_id,warehouse_id,status,changed_by_membership_id,changed_at) "
                        + "values (?,?,?,?,'ACTIVE',?,current_timestamp)", tenant, workspace,
                UUID.fromString(membershipId(WAREHOUSE_EMAIL)), grantedWarehouse, UUID.fromString(membershipId(OWNER_EMAIL)));
        String warehouse = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        var response = mockMvc.perform(get("/api/v1/inventory/lots").param("catalogItemId", sku.item())
                        .param("size", "100").header("Authorization", warehouse))
                .andExpect(status().isOk()).andReturn();
        var ids = new java.util.ArrayList<String>();
        tools.jackson.databind.json.JsonMapper.shared().readTree(response.getResponse().getContentAsString()).get("items").forEach(item -> ids.add(item.get("id").asText()));
        org.assertj.core.api.Assertions.assertThat(ids).contains(grantedLot.toString()).doesNotContain(hiddenLot.toString());
        mockMvc.perform(get("/api/v1/inventory/lots/{id}", grantedLot).header("Authorization", warehouse))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(grantedLot.toString()));
        mockMvc.perform(get("/api/v1/inventory/lots/{id}", hiddenLot).header("Authorization", warehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/warehouses/{id}/inventory-availability", grantedWarehouse)
                        .param("catalogItemId", sku.item()).header("Authorization", warehouse))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/warehouses/{id}/inventory-availability", hiddenWarehouse)
                        .param("catalogItemId", sku.item()).header("Authorization", warehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/fulfillments/{id}", UUID.randomUUID()).header("Authorization", warehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/fulfillments/{id}/physical-allocation", UUID.randomUUID()).header("Authorization", warehouse))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inventory/lots").param("warehouseId", hiddenWarehouse.toString())
                        .header("Authorization", warehouse))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void pickingReadsAssignedWorkOnlyWithWarehouseGrant() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID warehouse = UUID.randomUUID(), lot = UUID.randomUUID(), fulfillment = UUID.randomUUID();
        UUID allocation = UUID.randomUUID(), backing = UUID.randomUUID(), order = UUID.randomUUID();
        UUID commitment = UUID.randomUUID(), actor = UUID.fromString(membershipId(WAREHOUSE_EMAIL));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var db = tenantJdbc();
        var sku = db.query("select id,coalesce(nullif(legacy_catalog_item_id,''),sku_code) item_id,unit_of_measure "
                        + "from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and status='ACTIVE' order by sku_code limit 1",
                (rs, row) -> new Sku(rs.getObject("id", UUID.class), rs.getString("item_id"), rs.getString("unit_of_measure")),
                tenant, workspace).stream().findFirst().orElseThrow();
        seedLot(db, tenant, workspace, warehouse, lot, "P" + suffix, sku);
        UUID zone = db.queryForObject("select zone_id from warehouse.inventory_lot where id=?", UUID.class, lot);
        db.update("insert into warehouse.inventory_backing (id,tenant_id,workspace_id,commercial_commitment_id,status,requested_at,completed_at,updated_at) values (?,?,?,?,'BACKED',current_timestamp,current_timestamp,current_timestamp)", backing, tenant, workspace, commitment);
        db.update("insert into warehouse.physical_allocation (id,tenant_id,workspace_id,sales_order_id,commercial_commitment_id,inventory_backing_id,fulfillment_id,status,allocated_at,created_at,updated_at,actor_membership_id,idempotency_key,request_hash) values (?,?,?,?,?,?,?,'ALLOCATED',current_timestamp,current_timestamp,current_timestamp,?,?,?)", allocation, tenant, workspace, order, commitment, backing, fulfillment, actor, suffix, "a".repeat(64));
        db.update("insert into warehouse.physical_allocation_line (id,tenant_id,workspace_id,physical_allocation_id,sku_id,catalog_item_id,warehouse_id,zone_id,lot_id,quantity,unit,expiration_date,created_at) values (?,?,?,?,?,?,?,?,?,1,?,current_date+30,current_timestamp)", UUID.randomUUID(), tenant, workspace, allocation, sku.id(), sku.item(), warehouse, zone, lot, sku.unit());
        db.update("insert into logistics.fulfillment (id,tenant_id,workspace_id,sales_order_id,physical_allocation_id,status,planned_at,created_at,updated_at,actor_membership_id) values (?,?,?,?,?,'ALLOCATED',current_timestamp,current_timestamp,current_timestamp,?)", fulfillment, tenant, workspace, order, allocation, actor);
        db.update("insert into logistics.fulfillment_line (id,tenant_id,workspace_id,fulfillment_id,sku_id,physical_allocation_id,catalog_item_id,ordered_quantity,backed_quantity,allocated_quantity,unit,created_at) values (?,?,?,?,?,?,?,1,1,1,?,current_timestamp)", UUID.randomUUID(), tenant, workspace, fulfillment, sku.id(), allocation, sku.item(), sku.unit());
        String token = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/fulfillments/{id}", fulfillment).header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/fulfillments/{id}/physical-allocation", fulfillment).header("Authorization", token)).andExpect(status().isNotFound());
        jdbc.update("insert into tenant_management.warehouse_access_grant (tenant_id,workspace_id,membership_id,warehouse_id,status,changed_by_membership_id,changed_at) values (?,?,?,?,'ACTIVE',?,current_timestamp)", tenant, workspace, actor, warehouse, UUID.fromString(membershipId(OWNER_EMAIL)));
        token = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        var list = mockMvc.perform(get("/api/v1/fulfillments").param("size", "100").header("Authorization", token)).andExpect(status().isOk()).andReturn();
        var ids = new java.util.ArrayList<String>();
        tools.jackson.databind.json.JsonMapper.shared().readTree(list.getResponse().getContentAsString()).get("items").forEach(item -> ids.add(item.get("fulfillmentId").asText()));
        org.assertj.core.api.Assertions.assertThat(ids).contains(fulfillment.toString());
        mockMvc.perform(get("/api/v1/fulfillments/{id}", fulfillment).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fulfillment.toString()));
        mockMvc.perform(get("/api/v1/fulfillments/{id}/physical-allocation", fulfillment).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocationId").value(allocation.toString()));
    }

    private static void seedLot(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID tenant, UUID workspace,
                                UUID warehouse, UUID lot, String suffix, Sku sku) {
        UUID zone = UUID.randomUUID();
        jdbc.update("insert into warehouse.warehouse (id,tenant_id,workspace_id,code,name,status,created_at,updated_at) "
                + "values (?,?,?,?,?,'ACTIVE',current_timestamp,current_timestamp)", warehouse, tenant, workspace, suffix, "Test warehouse");
        jdbc.update("insert into warehouse.storage_zone (id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,status,created_at,updated_at) "
                + "values (?,?,?,?,?,?,'AMBIENT','ACTIVE',current_timestamp,current_timestamp)", zone, tenant, workspace, warehouse, suffix, "Test zone");
        jdbc.update("insert into warehouse.inventory_lot (id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,sku_id) "
                + "values (?,?,?,?,?,?,?,current_date+30,current_timestamp,10,0,?,'AVAILABLE',?)",
                lot, tenant, workspace, warehouse, zone, sku.item(), suffix, sku.unit(), sku.id());
    }

    private record Sku(UUID id, String item, String unit) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadComposition {
        @Bean @Primary
        InventoryLotsRead cloudInventoryLotsReadForHttpTest(TenantBusinessDatabaseRouter router,
                TenantWarehouseOperationsCompositionFactory compositions, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantSalesOrderFulfillmentQueryFactory orders,
                TenantFulfillmentInventoryQueryFactory fulfillment, TenantBusinessEvidenceQueryFactory evidence,
                TenantChangeEventPersistenceFactory events, TenantBusinessTraceabilityBindingsFactory traceability,
                WarehouseObjectAccess warehouses, OperationalSettingsAccess settings) {
            return new MobileOperationsReadConfiguration.CloudReads().cloudInventoryLotsRead(router, compositions,
                    accounts, skus, orders, fulfillment, evidence, events, traceability, warehouses, settings);
        }

        @Bean @Primary
        PickingWorkRead cloudPickingWorkReadForHttpTest(TenantBusinessDatabaseRouter router,
                TenantFulfillmentDeliveryCompositionProviderAdapter compositions,
                WarehouseObjectAccess warehouses, WorkforceDirectory workforce) {
            return new MobileOperationsReadConfiguration.CloudReads().cloudPickingWorkRead(router, compositions,
                    warehouses, workforce);
        }
    }
}
