package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        grantWarehouseAccess(accessToken(OWNER_EMAIL, "PLATFORM"), membershipId(WAREHOUSE_EMAIL), warehouseId);
        token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
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
        assertThat(tenantJdbc().queryForObject("select stock_quantity from warehouse.inventory_lot where id=?", java.math.BigDecimal.class, UUID.fromString(lotId)))
                .isEqualByComparingTo("10");
        assertThat(tenantJdbc().queryForObject("select batch_number from warehouse.inventory_lot where id=?", String.class, UUID.fromString(lotId)))
                .isEqualTo("B-001");
        assertThat(tenantJdbc().queryForObject("select expiration_date from warehouse.inventory_lot where id=?", java.time.LocalDate.class, UUID.fromString(lotId)))
                .isEqualTo(java.time.LocalDate.of(2099, 1, 1));
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where aggregate_id=? and event_type='warehouse.lot.received'", Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=? and batch_number='B-001'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(warehouseId))).isEqualTo(1);
    }

    @Test void invalidInboundReceiptLeavesNoPartialInventoryOrIdempotencySuccess() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, "WH-INV-" + suffix);
        token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String zoneId = createZone(token, warehouseId, "Z-INV-" + suffix);
        String key = "inbound-invalid-" + suffix;
        String receipt = receiptBody(warehouseId, zoneId, "B-INV-" + suffix, "0");
        int lotsBefore = scopedWarehouseLotCount(warehouseId);
        int movementsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        int eventsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(scopedWarehouseLotCount(warehouseId)).isEqualTo(lotsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(movementsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(eventsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isZero();

        mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(receipt.replace("\"quantity\":\"0\"", "\"quantity\":\"3\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.onHand").value(3))
                .andExpect(jsonPath("$.batchNumber").value("B-INV-" + suffix));
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"omitted", "null", "malformed", "past", "today"})
    void invalidExpiryCreatesNoLotMovementEventOrSuccessfulIntent(String expiryCase) throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, "WH-EXP-" + suffix);
        token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String zoneId = createZone(token, warehouseId, "Z-EXP-" + suffix);
        String key = "inbound-expiry-" + suffix;
        String valid = receiptBody(warehouseId, zoneId, "B-EXP-" + suffix, "2.50");
        String expiryField = "\"expirationDate\":\"2099-01-01\"";
        String invalid = switch (expiryCase) {
            case "omitted" -> valid.replace(expiryField + ",", "");
            case "null" -> valid.replace(expiryField, "\"expirationDate\":null");
            case "malformed" -> valid.replace("2099-01-01", "2099-99-99");
            case "past" -> valid.replace("2099-01-01", java.time.LocalDate.now().minusDays(1).toString());
            case "today" -> valid.replace("2099-01-01", java.time.LocalDate.now().toString());
            default -> throw new IllegalArgumentException("Unknown expiry scenario");
        };
        int movementsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        int eventsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        postReceipt(token, key, invalid).andExpect(status().isBadRequest());

        assertThat(scopedWarehouseLotCount(warehouseId)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(movementsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(eventsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isZero();

        postReceipt(token, key, valid).andExpect(status().isCreated())
                .andExpect(jsonPath("$.expirationDate").value("2099-01-01"))
                .andExpect(jsonPath("$.onHand").value(2.5))
                .andExpect(jsonPath("$.batchNumber").value("B-EXP-" + suffix));
        assertThat(scopedWarehouseLotCount(warehouseId)).isEqualTo(1);
    }

    @Test void inboundReceiptScopeAndIdempotencyAreIsolatedByTenantWorkspace() throws Exception {
        String baseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        UUID baseTenant = UUID.fromString(tenantId());
        UUID baseWorkspace = UUID.fromString(workspaceId());
        String baseWarehouse = createWarehouse(baseToken, "WH-SCOPE-A-" + suffix);
        baseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String baseZone = createZone(baseToken, baseWarehouse, "Z-SCOPE-A-" + suffix);

        WorkspaceScope otherScope = createAdditionalTenantAndWorkspace(WAREHOUSE_EMAIL, suffix);
        var otherTenantJdbc = tenantJdbcFor(UUID.fromString(otherScope.tenantId()));
        String otherScopeToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, "PLATFORM", otherScope.slug());
        String otherScopeOwnerToken = accessTokenForWorkspace(OWNER_EMAIL, "PLATFORM", otherScope.slug());
        String otherScopeWarehouse = createWarehouse(otherScopeToken, "WH-SCOPE-B-" + suffix,
                otherScopeOwnerToken, otherScope.warehouseMembershipId());
        otherScopeToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, "PLATFORM", otherScope.slug());

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
        assertThat(tenantJdbc().queryForObject("select tenant_id from warehouse.inventory_lot where id=?", UUID.class, UUID.fromString(baseLot))).isEqualTo(baseTenant);
        assertThat(tenantJdbc().queryForObject("select workspace_id from warehouse.inventory_lot where id=?", UUID.class, UUID.fromString(baseLot))).isEqualTo(baseWorkspace);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, key)).isEqualTo(1);
        assertThat(otherTenantJdbc.queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, key)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_lot where batch_number='B-CROSS-' || ?",
                Integer.class, suffix)).isZero();
        assertThat(otherTenantJdbc.queryForObject("select count(*) from warehouse.inventory_lot where batch_number='B-CROSS-' || ?",
                Integer.class, suffix)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, "inbound-cross-scope-" + suffix)).isZero();
        assertThat(otherTenantJdbc.queryForObject("select count(*) from warehouse.command_idempotency where operation='inbound' and idempotency_key=?",
                Integer.class, "inbound-cross-scope-" + suffix)).isZero();
    }

    @Test void outOfRangeReceiptRequiresExactWarehousePhotoThenCreatesAtomicPreventiveHold() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouse = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-H-"+suffix+"\",\"name\":\"Hold Warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouse).get("id").asText();
        grantWarehouseAccess(accessToken(OWNER_EMAIL, "PLATFORM"), membershipId(WAREHOUSE_EMAIL), warehouseId);
        token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        var skuRanges = tenantJdbc().query("select temperature_min,temperature_max from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002'",
                (rs, row) -> new java.math.BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)},
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        assertThat(skuRanges).hasSize(1);
        java.math.BigDecimal skuMinimum = skuRanges.getFirst()[0];
        java.math.BigDecimal skuMaximum = skuRanges.getFirst()[1];
        java.math.BigDecimal zoneMinimum = java.math.BigDecimal.valueOf(-5);
        java.math.BigDecimal zoneMaximum = java.math.BigDecimal.valueOf(5);
        if (skuMinimum != null) {
            zoneMinimum = zoneMinimum.min(skuMinimum);
            zoneMaximum = zoneMaximum.max(skuMinimum);
        }
        if (skuMaximum != null) {
            zoneMinimum = zoneMinimum.min(skuMaximum);
            zoneMaximum = zoneMaximum.max(skuMaximum);
        }
        String zone = mockMvc.perform(post("/api/v1/warehouses/"+warehouseId+"/zones").header("Authorization", "Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"C-"+suffix+"\",\"name\":\"Chilled QA\",\"type\":\"CHILLED\",\"temperatureMin\":"
                                + zoneMinimum.toPlainString() + ",\"temperatureMax\":" + zoneMaximum.toPlainString() + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zone).get("id").asText();
        java.math.BigDecimal acceptedMinimum = skuMinimum == null ? zoneMinimum : skuMinimum.max(zoneMinimum);
        java.math.BigDecimal acceptedMaximum = skuMaximum == null ? zoneMaximum : skuMaximum.min(zoneMaximum);
        assertThat(acceptedMinimum).isLessThanOrEqualTo(acceptedMaximum);

        MvcResult inRange = postReceipt(token, "in-range-receipt-" + suffix,
                receiptBody(warehouseId, zoneId, "I-" + suffix, "10",
                        acceptedMinimum.add(acceptedMaximum).divide(java.math.BigDecimal.valueOf(2))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        String inRangeLotId = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(inRange.getResponse().getContentAsString()).get("id").asText();
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=?",
                Integer.class, UUID.fromString(inRangeLotId))).isZero();

        int lotsBefore = scopedWarehouseLotCount(warehouseId);
        int movementsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        int eventsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        int evaluationsBefore = tenantJdbc().queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        String key = "excursion-receipt-" + suffix;
        java.math.BigDecimal excursionReading = acceptedMaximum.add(java.math.BigDecimal.ONE);
        postReceipt(token, key, receiptBody(warehouseId, zoneId, "H-" + suffix, "10", excursionReading))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));

        assertThat(scopedWarehouseLotCount(warehouseId)).isEqualTo(lotsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_lot where warehouse_id=? and batch_number=?",
                Integer.class, UUID.fromString(warehouseId), "H-" + suffix)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(movementsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where tenant_id=? and workspace_id=? and event_type in ('warehouse.lot.received','warehouse.lot.temperature-hold')",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(eventsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(evaluationsBefore);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation='inbound' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), key)).isZero();

        String evidence = mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(new MockMultipartFile("file", "receiving-photo.png", "image/png",
                                java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/g5sAAAAASUVORK5CYII=")))
                        .param("subjectType", "WAREHOUSE").param("subjectId", warehouseId)
                        .header("Authorization", "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM"))
                        .header("Idempotency-Key", "receiving-photo-" + suffix))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String evidenceId = tools.jackson.databind.json.JsonMapper.shared().readTree(evidence).get("id").asText();
        MvcResult heldReceipt = postReceipt(token, key,
                        receiptBody(warehouseId, zoneId, "H-" + suffix, "10", excursionReading)
                                .replace("\"temperatureReading\":" + excursionReading.toPlainString(),
                                        "\"temperatureReading\":" + excursionReading.toPlainString()
                                                + ",\"temperatureEvidenceObjectId\":\"" + evidenceId + "\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("HOLD"))
                .andExpect(jsonPath("$.available").value(0)).andExpect(jsonPath("$.onHand").value(10))
                .andReturn();
        String heldLotId = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(heldReceipt.getResponse().getContentAsString()).get("id").asText();
        assertThat(tenantJdbc().queryForObject("select temperature_evidence_object_id from warehouse.inventory_lot where id=?",
                UUID.class, UUID.fromString(heldLotId))).isEqualTo(UUID.fromString(evidenceId));
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_lot where id=? "
                        + "and temperature_recorded_by_membership_id is not null and temperature_recorded_at is not null",
                Integer.class, UUID.fromString(heldLotId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_temperature_evaluation "
                        + "where lot_id=? and status='OPEN' and disposition='HOLD' and evidence_object_id=? "
                        + "and affected_quantity=10 and actor_membership_id is not null",
                Integer.class, UUID.fromString(heldLotId), UUID.fromString(evidenceId))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where lot_id=? and movement_type='INBOUND_RECEIPT'",
                Integer.class, UUID.fromString(heldLotId))).isEqualTo(1);
    }

    private String createWarehouse(String token, String code) throws Exception {
        return createWarehouse(token, code, accessToken(OWNER_EMAIL, "PLATFORM"), membershipId(WAREHOUSE_EMAIL));
    }

    private String createWarehouse(String token, String code, String grantAdminToken, String targetMembershipId) throws Exception {
        String result = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Receiving test warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(result).get("id").asText();
        grantWarehouseAccess(grantAdminToken, targetMembershipId, warehouseId);
        return warehouseId;
    }

    private void grantWarehouseAccess(String grantAdminToken, String targetMembershipId, String warehouseId) throws Exception {
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + grantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + targetMembershipId + "\"}"))
                .andExpect(status().isOk());
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

    private static String receiptBody(String warehouseId, String zoneId, String batchNumber, String quantity,
                                      java.math.BigDecimal temperatureReading) {
        String receipt = receiptBody(warehouseId, zoneId, batchNumber, quantity);
        return receipt.substring(0, receipt.length() - 1) + ",\"temperatureReading\":"
                + temperatureReading.toPlainString() + "}";
    }

    private ResultActions postReceipt(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/inbound-receipts").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int scopedWarehouseLotCount(String warehouseId) {
        return tenantJdbc().queryForObject("select count(*) from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=?",
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
        WorkspaceScope scope = createWorkspaceMembership(email, tenantId, "w4-rec-" + normalizedSuffix);
        provisionTenantBusinessDatabase(tenantId, UUID.fromString(scope.workspaceId()));
        return scope;
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
        copyWorkspaceMembership(OWNER_EMAIL, tenantId, workspaceId);
        return new WorkspaceScope(tenantId.toString(), workspaceId.toString(), slug, newMembership.toString());
    }

    private void copyWorkspaceMembership(String email, UUID tenantId, UUID workspaceId) {
        UUID sourceMembership = UUID.fromString(membershipId(email));
        UUID newMembership = UUID.randomUUID();
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
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private record WorkspaceScope(String tenantId, String workspaceId, String slug, String warehouseMembershipId) { }
}
