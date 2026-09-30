package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseApiIntegrationTests extends PostgresIntegrationSupport {
    @Test void buyerWarehouseProjectionDoesNotWriteInsideReadOnlyRequest() throws Exception {
        String token = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/buyer/warehouses").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test void warehouseZoneAndInboundReceiptAreScopedAndLedgered() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WH-"+suffix+"\",\"name\":\"Test Warehouse\",\"address\":\"Lima\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Z-"+suffix+"\",\"name\":\"Ambient\",\"type\":\"AMBIENT\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        String receiptRequest = "{\"warehouseId\":\""+warehouseId+"\",\"zoneId\":\""+zoneId+"\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"B-001\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\"}";
        String key = "inbound-replay-" + suffix;
        String receipt = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.available").value(10))
            .andExpect(jsonPath("$.onHand").value(10)).andExpect(jsonPath("$.batchNumber").value("B-001"))
            .andExpect(jsonPath("$.expirationDate").value("2099-01-01")).andExpect(jsonPath("$.unit").value("UNIT"))
            .andReturn().getResponse().getContentAsString();
        String lotId = tools.jackson.databind.json.JsonMapper.shared().readTree(receipt).get("id").asText();
        String replay = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.available").value(10))
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(tools.jackson.databind.json.JsonMapper.shared().readTree(replay).get("id").asText()).isEqualTo(lotId);
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(receiptRequest.replace("\"quantity\":\"10\"", "\"quantity\":\"12\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertThat(jdbc.queryForObject("select stock_quantity from warehouse.inventory_lot where id=?", java.math.BigDecimal.class, UUID.fromString(lotId)))
                .isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select batch_number from warehouse.inventory_lot where id=?", String.class, UUID.fromString(lotId)))
                .isEqualTo("B-001");
        assertThat(jdbc.queryForObject("select expiration_date from warehouse.inventory_lot where id=?", java.time.LocalDate.class, UUID.fromString(lotId)))
                .isEqualTo(java.time.LocalDate.of(2099, 1, 1));
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_event where aggregate_id=? and event_type='warehouse.lot.received'", Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=? and batch_number='B-001'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(warehouseId))).isEqualTo(1);
    }

    @Test void invalidInboundReceiptLeavesNoPartialInventoryOrIdempotencySuccess() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, "WH-INV-" + suffix);
        String zoneId = createZone(token, warehouseId, "Z-INV-" + suffix);
        String key = "inbound-invalid-" + suffix;
        String receipt = receiptBody(warehouseId, zoneId, "B-INV-" + suffix, "0");
        int lotsBefore = scopedWarehouseLotCount(warehouseId);
        int movementsBefore = jdbc.queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        int eventsBefore = jdbc.queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(scopedWarehouseLotCount(warehouseId)).isEqualTo(lotsBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(movementsBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isZero();

        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(receipt.replace("\"quantity\":\"0\"", "\"quantity\":\"3\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.onHand").value(3))
                .andExpect(jsonPath("$.batchNumber").value("B-INV-" + suffix));
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isEqualTo(1);
    }

    @Test void inboundReceiptScopeAndIdempotencyAreIsolatedByTenantWorkspace() throws Exception {
        String baseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        UUID baseTenant = UUID.fromString(tenantId());
        UUID baseWorkspace = UUID.fromString(workspaceId());
        String baseWarehouse = createWarehouse(baseToken, "WH-SCOPE-A-" + suffix);
        String baseZone = createZone(baseToken, baseWarehouse, "Z-SCOPE-A-" + suffix);

        WorkspaceScope otherScope = createAdditionalTenantAndWorkspace(WAREHOUSE_EMAIL, suffix);
        String otherScopeToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, "PLATFORM", otherScope.slug());
        String otherScopeWarehouse = createWarehouse(otherScopeToken, "WH-SCOPE-B-" + suffix);

        String key = "inbound-scope-" + suffix;
        String baseBody = receiptBody(baseWarehouse, baseZone, "B-SCOPE-" + suffix, "4");

        String baseReceipt = postReceipt(baseToken, key, baseBody).andExpect(status().isCreated())
                .andExpect(jsonPath("$.available").value(4)).andReturn().getResponse().getContentAsString();
        String foreignScopeAttempt = receiptBody(otherScopeWarehouse, baseZone, "B-CROSS-" + suffix, "4");
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + baseToken)
                        .header("Idempotency-Key", "inbound-cross-scope-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(foreignScopeAttempt))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
        postReceipt(otherScopeToken, key, baseBody).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));

        String baseLot = tools.jackson.databind.json.JsonMapper.shared().readTree(baseReceipt).get("id").asText();
        assertThat(jdbc.queryForObject("select tenant_id from warehouse.inventory_lot where id=?", UUID.class, UUID.fromString(baseLot))).isEqualTo(baseTenant);
        assertThat(jdbc.queryForObject("select workspace_id from warehouse.inventory_lot where id=?", UUID.class, UUID.fromString(baseLot))).isEqualTo(baseWorkspace);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(otherScope.tenantId()), UUID.fromString(otherScope.workspaceId()), key)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot where batch_number='B-CROSS-' || ?",
                Integer.class, suffix)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, "inbound-cross-scope-" + suffix)).isZero();
    }

    @Test void outOfRangeReceiptIsHeldUntilExplicitDisposition() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-H-"+suffix+"\",\"name\":\"Hold Warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"C-"+suffix+"\",\"name\":\"Chilled QA\",\"type\":\"CHILLED\",\"temperatureMin\":-5,\"temperatureMax\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        String receiptRequest = "{\"warehouseId\":\""+warehouseId+"\",\"zoneId\":\""+zoneId+"\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"H-"+suffix+"\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\",\"temperatureReading\":10}";
        MvcResult receipt = mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer "+token)
                        .header("Idempotency-Key", "hold-receipt-"+suffix).contentType(MediaType.APPLICATION_JSON).content(receiptRequest))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("HOLD")).andReturn();
        String lotId = tools.jackson.databind.json.JsonMapper.shared().readTree(receipt.getResponse().getContentAsString()).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("OPEN");
        String etag = receipt.getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/inventory/lots/"+lotId+"/dispositions").header("Authorization", "Bearer "+token)
                        .header("If-Match", etag).header("Idempotency-Key", "hold-disposition-"+suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disposition\":\"RELEASE\",\"reason\":\"QA cleared\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select disposition from warehouse.inventory_lot_disposition where lot_id=?", String.class, java.util.UUID.fromString(lotId))).isEqualTo("RELEASE");
    }

    private String createWarehouse(String token, String code) throws Exception {
        String result = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Receiving test warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result).get("id").asText();
    }

    private String createZone(String token, String warehouseId, String code) throws Exception {
        String result = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Ambient receiving\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result).get("id").asText();
    }

    private static String receiptBody(String warehouseId, String zoneId, String batchNumber, String quantity) {
        return "{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"" + batchNumber
                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"" + quantity
                + "\",\"unit\":\"UNIT\"}";
    }

    private ResultActions postReceipt(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int scopedWarehouseLotCount(String warehouseId) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(warehouseId));
    }

    private String accessTokenForWorkspace(String email, String surface, String workspaceSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authentication/sign-in").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
                                + "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"" + surface + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private WorkspaceScope createAdditionalTenantAndWorkspace(String email, String suffix) {
        UUID tenantId = UUID.randomUUID();
        String normalizedSuffix = suffix.toLowerCase(java.util.Locale.ROOT);
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenantId, "Receiving isolation tenant", "w4-rec-tenant-" + normalizedSuffix);
        return createWorkspaceMembership(email, tenantId, "w4-rec-" + normalizedSuffix);
    }

    private WorkspaceScope createWorkspaceMembership(String email, UUID tenantId, String slug) {
        UUID sourceMembership = UUID.fromString(membershipId(email));
        UUID workspaceId = UUID.randomUUID();
        UUID newMembership = UUID.randomUUID();
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspaceId, tenantId, "Receiving isolation workspace", slug);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "select ?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                        + "from tenant_management.workspace_membership where id=?",
                newMembership, workspaceId, sourceMembership);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition "
                        + "where membership_id=?",
                newMembership, tenantId, workspaceId, sourceMembership);
        return new WorkspaceScope(tenantId.toString(), workspaceId.toString(), slug);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private record WorkspaceScope(String tenantId, String workspaceId, String slug) { }
}
