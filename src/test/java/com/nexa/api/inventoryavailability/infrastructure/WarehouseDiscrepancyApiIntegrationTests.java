package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseDiscrepancyApiIntegrationTests extends PostgresIntegrationSupport {
    @Test
    void adjustmentRejectsUnsupportedDirectionAndMissingReasonWithoutWritingStockFacts() throws Exception {
        WarehouseLot lot = receiveLot(false);
        int movementsBefore = movementCount(lot.id());
        int eventsBefore = eventCount(lot.id());

        mockMvc.perform(post("/api/v1/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "adjust-direction-" + lot.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(lot.id(), "SIDEWAYS", "2", "count variance")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "adjust-no-reason-" + lot.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(lot.id(), "OUT", "2", "   ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        String longReasonKey = "adjust-long-reason-" + lot.suffix();
        mockMvc.perform(post("/api/v1/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", longReasonKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(lot.id(), "OUT", "2", "x".repeat(2001))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertLot(lot.id(), "10", "AVAILABLE", 0);
        assertThat(movementCount(lot.id())).isEqualTo(movementsBefore);
        assertThat(eventCount(lot.id())).isEqualTo(eventsBefore);
        assertThat(commandCount("adjustment", "adjust-direction-" + lot.suffix())).isZero();
        assertThat(commandCount("adjustment", "adjust-no-reason-" + lot.suffix())).isZero();
        assertThat(commandCount("adjustment", longReasonKey)).isZero();
    }

    @Test
    void dispositionRequiresCurrentAuthorityReasonAndEvidenceAndIsImmutableByKey() throws Exception {
        WarehouseLot thermalHold = receiveLot(true);
        String invalidKey = "disposition-no-reason-" + thermalHold.suffix();

        mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", invalidKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "  ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertOpenTemperatureEvidence(thermalHold.id());
        assertLot(thermalHold.id(), "10", "HOLD", 0);
        assertThat(dispositionCount(thermalHold.id())).isZero();
        assertThat(eventCount(thermalHold.id(), "warehouse.lot.disposition-recorded")).isZero();

        MvcResult released = mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", "disposition-release-" + thermalHold.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Temperature reviewed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        String key = "disposition-release-" + thermalHold.suffix();
        String currentEtag = released.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Temperature reviewed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));

        mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("HOLD", "Temperature reviewed")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Different review")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        mockMvc.perform(post(dispositionPath(thermalHold.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(thermalHold.token()))
                        .header(HttpHeaders.IF_MATCH, thermalHold.etag())
                        .header("Idempotency-Key", "disposition-stale-" + thermalHold.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Temperature reviewed")))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(currentEtag).isEqualTo("\"1\"");
        assertThat(dispositionCount(thermalHold.id())).isEqualTo(1);
        assertThat(eventCount(thermalHold.id(), "warehouse.lot.disposition-recorded")).isEqualTo(1);
        assertThat(movementCount(thermalHold.id())).isEqualTo(1);
        assertLot(thermalHold.id(), "10", "AVAILABLE", 1);
        assertResolvedTemperatureEvidence(thermalHold.id());
        assertThat(commandCount("lot-disposition", key)).isEqualTo(1);
    }

    @Test
    void dispositionRequiresBothExactPermissionAndCurrentWarehouseGrant() throws Exception {
        WarehouseLot lot = receiveLot(true);
        String suffix = lot.suffix();
        grantWarehouseAccess(accessToken(OWNER_EMAIL, "PLATFORM"), membershipId(SALES_EMAIL), lot.warehouseId());
        String salesToken = accessToken(SALES_EMAIL, "PLATFORM");

        mockMvc.perform(post(dispositionPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(salesToken))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "disposition-no-permission-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Temperature reviewed")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/warehouses/" + lot.warehouseId() + "/access-grants/" + membershipId(WAREHOUSE_EMAIL))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(OWNER_EMAIL, "PLATFORM")))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(post(dispositionPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(WAREHOUSE_EMAIL, "PLATFORM")))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "disposition-no-grant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Temperature reviewed")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));

        assertOpenTemperatureEvidence(lot.id());
        assertLot(lot.id(), "10", "HOLD", 0);
        assertThat(dispositionCount(lot.id())).isZero();
        assertThat(eventCount(lot.id(), "warehouse.lot.disposition-recorded")).isZero();
        assertThat(commandCount("lot-disposition", "disposition-no-permission-" + suffix)).isZero();
        assertThat(commandCount("lot-disposition", "disposition-no-grant-" + suffix)).isZero();
    }

    @Test
    void releaseOfManuallyHeldLotRemainsSupportedWithoutTemperatureEvaluation() throws Exception {
        WarehouseLot lot = receiveLot(false);
        mockMvc.perform(post(dispositionPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "manual-hold-" + lot.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("HOLD", "Physical count review")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOLD"));

        String currentEtag = "\"1\"";
        mockMvc.perform(post(dispositionPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, currentEtag)
                        .header("Idempotency-Key", "release-without-evidence-" + lot.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("RELEASE", "Physical count reviewed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));

        assertLot(lot.id(), "10", "AVAILABLE", 2);
        assertThat(openTemperatureEvidenceCount(lot.id())).isZero();
        assertThat(dispositionCount(lot.id())).isEqualTo(2);
        assertThat(commandCount("lot-disposition", "release-without-evidence-" + lot.suffix())).isEqualTo(1);
    }

    @Test
    void cycleCountCorrectionIsAppendOnlyIdempotentAndRequiresExplicitCurrentVersionApplication() throws Exception {
        WarehouseLot lot = receiveLot(false);
        int movementsBefore = movementCount(lot.id());
        int eventsBefore = eventCount(lot.id());
        String countKey = "cycle-count-" + lot.suffix();
        String countBody = "{\"observedQuantity\":\"8\",\"unit\":\"UNIT\"}";

        MvcResult counted = mockMvc.perform(post(cycleCountPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", countKey)
                        .contentType(MediaType.APPLICATION_JSON).content(countBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.lotVersion").value(0))
                .andExpect(jsonPath("$.expectedQuantity").value(10))
                .andExpect(jsonPath("$.observedQuantity").value(8))
                .andExpect(jsonPath("$.actorMembershipId").value(membershipId(WAREHOUSE_EMAIL)))
                .andReturn();
        String countId = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(counted.getResponse().getContentAsString()).get("id").asText();

        assertLot(lot.id(), "10", "AVAILABLE", 0);
        assertThat(movementCount(lot.id())).isEqualTo(movementsBefore);
        assertThat(eventCount(lot.id())).isEqualTo(eventsBefore);

        MvcResult replayedCount = mockMvc.perform(post(cycleCountPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", countKey)
                        .contentType(MediaType.APPLICATION_JSON).content(countBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(tools.jackson.databind.json.JsonMapper.shared().readTree(replayedCount.getResponse().getContentAsString())
                .get("id").asText()).isEqualTo(countId);
        mockMvc.perform(post(cycleCountPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", countKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedQuantity\":\"7\",\"unit\":\"UNIT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        String correctionPath = correctionPath(countId);
        String correctionKey = "cycle-correction-" + lot.suffix();
        MvcResult applied = mockMvc.perform(post(correctionPath)
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", correctionKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleCountId").value(countId))
                .andExpect(jsonPath("$.quantityBefore").value(10))
                .andExpect(jsonPath("$.quantityAfter").value(8))
                .andExpect(jsonPath("$.quantityDelta").value(-2))
                .andExpect(jsonPath("$.lotVersionBefore").value(0))
                .andExpect(jsonPath("$.lotVersionAfter").value(1))
                .andExpect(jsonPath("$.actorMembershipId").value(membershipId(WAREHOUSE_EMAIL)))
                .andReturn();
        assertLot(lot.id(), "8", "AVAILABLE", 1);
        assertThat(movementCount(lot.id())).isEqualTo(movementsBefore + 1);
        assertThat(eventCount(lot.id())).isEqualTo(eventsBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_cycle_count_correction where cycle_count_id=?",
                Integer.class, UUID.fromString(countId))).isEqualTo(1);

        MvcResult replayedCorrection = mockMvc.perform(post(correctionPath)
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", correctionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(tools.jackson.databind.json.JsonMapper.shared()
                .readTree(replayedCorrection.getResponse().getContentAsString()).get("id").asText())
                .isEqualTo(tools.jackson.databind.json.JsonMapper.shared()
                        .readTree(applied.getResponse().getContentAsString()).get("id").asText());
        assertLot(lot.id(), "8", "AVAILABLE", 1);
        assertThat(movementCount(lot.id())).isEqualTo(movementsBefore + 1);
        assertThat(commandCount("inventory-cycle-count-correction", correctionKey)).isEqualTo(1);
    }

    @Test
    void cycleCountCorrectionRejectsStaleSnapshotAndRechecksPermissionAndWarehouseGrant() throws Exception {
        WarehouseLot lot = receiveLot(false);
        String countKey = "cycle-count-stale-" + lot.suffix();
        MvcResult counted = mockMvc.perform(post(cycleCountPath(lot.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", countKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observedQuantity\":\"8\",\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated()).andReturn();
        String countId = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(counted.getResponse().getContentAsString()).get("id").asText();
        String path = correctionPath(countId);

        mockMvc.perform(post("/api/v1/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "intervening-adjustment-" + lot.suffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(lot.id(), "IN", "1", "Count snapshot changed")))
                .andExpect(status().isOk());

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(lot.token()))
                        .header(HttpHeaders.IF_MATCH, lot.etag())
                        .header("Idempotency-Key", "stale-cycle-correction-" + lot.suffix()))
                .andExpect(status().isPreconditionFailed());
        assertLot(lot.id(), "11", "AVAILABLE", 1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_cycle_count_correction where cycle_count_id=?",
                Integer.class, UUID.fromString(countId))).isZero();

        String salesToken = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(salesToken))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", "cycle-correction-no-permission-" + lot.suffix()))
                .andExpect(status().isForbidden());
        assertThat(commandCount("inventory-cycle-count-correction", "cycle-correction-no-permission-" + lot.suffix())).isZero();

        mockMvc.perform(delete("/api/v1/warehouses/" + lot.warehouseId() + "/access-grants/" + membershipId(WAREHOUSE_EMAIL))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(OWNER_EMAIL, "PLATFORM")))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(WAREHOUSE_EMAIL, "PLATFORM")))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", "cycle-correction-no-grant-" + lot.suffix()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
        assertThat(commandCount("inventory-cycle-count-correction", "cycle-correction-no-grant-" + lot.suffix())).isZero();
        assertLot(lot.id(), "11", "AVAILABLE", 1);
    }

    private WarehouseLot receiveLot(boolean temperatureExcursion) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouseBody = mockMvc.perform(post("/api/v1/warehouses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-DISC-" + suffix + "\",\"name\":\"Discrepancy test\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouseBody).get("id").asText();
        grantWarehouseAccess(accessToken(OWNER_EMAIL, "PLATFORM"), membershipId(WAREHOUSE_EMAIL), warehouseId);
        token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String zoneRequest = temperatureExcursion
                ? "{\"code\":\"ZONE-" + suffix + "\",\"name\":\"Chilled QA\",\"type\":\"CHILLED\",\"temperatureMin\":-5,\"temperatureMax\":5}"
                : "{\"code\":\"ZONE-" + suffix + "\",\"name\":\"Ambient count\",\"type\":\"AMBIENT\"}";
        String zoneBody = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(zoneRequest))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zoneBody).get("id").asText();
        String receipt = "{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"DISC-" + suffix
                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\""
                + (temperatureExcursion ? ",\"temperatureReading\":10}" : "}");
        MvcResult received = mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).header("Idempotency-Key", "receive-disc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(temperatureExcursion ? "HOLD" : "AVAILABLE"))
                .andReturn();
        var body = tools.jackson.databind.json.JsonMapper.shared().readTree(received.getResponse().getContentAsString());
        return new WarehouseLot(body.get("id").asText(), warehouseId, token,
                received.getResponse().getHeader(HttpHeaders.ETAG), suffix);
    }

    private void grantWarehouseAccess(String ownerToken, String membershipId, String warehouseId) throws Exception {
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId + "\"}"))
                .andExpect(status().isOk());
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String adjustmentBody(String lotId, String direction, String quantity, String reason) {
        return "{\"lotId\":\"" + lotId + "\",\"quantity\":\"" + quantity + "\",\"direction\":\""
                + direction + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String dispositionPath(String lotId) { return "/api/v1/inventory/lots/" + lotId + "/dispositions"; }

    private static String cycleCountPath(String lotId) { return "/api/v1/inventory/lots/" + lotId + "/cycle-counts"; }

    private static String correctionPath(String countId) { return "/api/v1/inventory/cycle-counts/" + countId + "/corrections"; }

    private static String dispositionBody(String disposition, String reason) {
        return "{\"disposition\":\"" + disposition + "\",\"reason\":\"" + reason + "\"}";
    }

    private void assertLot(String lotId, String quantity, String status, long version) {
        var state = jdbc.queryForMap("select stock_quantity,status,version from warehouse.inventory_lot where id=?", UUID.fromString(lotId));
        assertThat((java.math.BigDecimal) state.get("stock_quantity")).isEqualByComparingTo(quantity);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(((Number) state.get("version")).longValue()).isEqualTo(version);
    }

    private int movementCount(String lotId) {
        return jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, UUID.fromString(lotId));
    }

    private int eventCount(String lotId) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_event where aggregate_id=?", Integer.class, UUID.fromString(lotId));
    }

    private int eventCount(String lotId, String eventType) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_event where aggregate_id=? and event_type=?",
                Integer.class, UUID.fromString(lotId), eventType);
    }

    private int dispositionCount(String lotId) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_lot_disposition where lot_id=?", Integer.class, UUID.fromString(lotId));
    }

    private int openTemperatureEvidenceCount(String lotId) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=? and status='OPEN'",
                Integer.class, UUID.fromString(lotId));
    }

    private int commandCount(String operation, String key) {
        return jdbc.queryForObject("select count(*) from warehouse.command_idempotency where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), operation, key);
    }

    private void assertOpenTemperatureEvidence(String lotId) { assertThat(openTemperatureEvidenceCount(lotId)).isEqualTo(1); }

    private void assertResolvedTemperatureEvidence(String lotId) {
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=? and status='RESOLVED' and disposition='RELEASE'",
                Integer.class, UUID.fromString(lotId))).isEqualTo(1);
    }

    private record WarehouseLot(String id, String warehouseId, String token, String etag, String suffix) { }
}
