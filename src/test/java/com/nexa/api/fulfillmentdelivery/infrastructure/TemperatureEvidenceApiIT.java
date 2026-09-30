package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TemperatureEvidenceApiIT extends NexaWorkflowIntegrationSupport {
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-30T11:22:33Z");

    @Test
    void recordsLotAndWarehouseFactsWithExactValueAndIdempotentReplayWithoutStockDisposition() throws Exception {
        ensureCommercialInventory();
        TemperatureSubject subject = createTemperatureSubject();
        BigDecimal stockBefore = jdbc.queryForObject("select stock_quantity from warehouse.inventory_lot where id=?",
                BigDecimal.class, subject.lotId());
        BigDecimal reservedBefore = jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?",
                BigDecimal.class, subject.lotId());
        long versionBefore = jdbc.queryForObject("select version from warehouse.inventory_lot where id=?",
                Long.class, subject.lotId());
        int movementsBefore = jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?",
                Integer.class, subject.lotId());

        String key = "temperature-evidence-" + uuid();
        BigDecimal exactValue = new BigDecimal("10.123456789");
        String lotBody = body("LOT", subject.lotId(), exactValue, "CELSIUS", OCCURRED_AT);
        MvcResult created = record(subject.token(), key, lotBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("LOT"))
                .andExpect(jsonPath("$.subjectId").value(subject.lotId().toString()))
                .andExpect(jsonPath("$.warehouseId").value(subject.warehouseId().toString()))
                .andExpect(jsonPath("$.unit").value("CELSIUS"))
                .andExpect(jsonPath("$.status").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andReturn();
        UUID evidenceId = UUID.fromString(json(created).get("id").asText());
        assertThat(json(created).get("value").decimalValue()).isEqualByComparingTo(exactValue);
        assertThat(Instant.parse(json(created).get("occurredAt").asText())).isEqualTo(OCCURRED_AT);
        assertThat(json(created).get("actorMembershipId").asText()).isEqualTo(membershipId(WAREHOUSE_EMAIL));

        MvcResult replay = record(subject.token(), key, lotBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(evidenceId.toString()))
                .andReturn();
        assertThat(json(replay).get("value").decimalValue()).isEqualByComparingTo(exactValue);
        record(subject.token(), key, body("LOT", subject.lotId(), new BigDecimal("10.123456788"), "CELSIUS", OCCURRED_AT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        String warehouseKey = "temperature-evidence-" + uuid();
        MvcResult warehouseEvidence = record(subject.token(), warehouseKey,
                        body("WAREHOUSE", subject.warehouseId(), new BigDecimal("-18.7654321"), "CELSIUS", OCCURRED_AT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("WAREHOUSE"))
                .andExpect(jsonPath("$.subjectId").value(subject.warehouseId().toString()))
                .andExpect(jsonPath("$.status").value("OUT_OF_RANGE"))
                .andReturn();
        UUID warehouseEvidenceId = UUID.fromString(json(warehouseEvidence).get("id").asText());

        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where id=?", Integer.class, evidenceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where id=?", Integer.class, warehouseEvidenceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_command_idempotency where operation='STOCK_TEMPERATURE_EVIDENCE' and idempotency_key in (?,?)",
                Integer.class, key, warehouseKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where event_type='TemperatureEvidenceRecorded.v1' and aggregate_id in (?,?)",
                Integer.class, evidenceId, warehouseEvidenceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select stock_quantity from warehouse.inventory_lot where id=?", BigDecimal.class, subject.lotId()))
                .isEqualByComparingTo(stockBefore);
        assertThat(jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class, subject.lotId()))
                .isEqualByComparingTo(reservedBefore);
        assertThat(jdbc.queryForObject("select version from warehouse.inventory_lot where id=?", Long.class, subject.lotId()))
                .isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, subject.lotId()))
                .isEqualTo(movementsBefore);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=?", Integer.class,
                subject.lotId())).isZero();
    }

    @Test
    void rejectsIncompleteAndCrossTenantSubjectsWithoutCreatingEvidence() throws Exception {
        ensureCommercialInventory();
        TemperatureSubject subject = createTemperatureSubject();
        String keyPrefix = "temperature-invalid-" + uuid();
        int before = jdbc.queryForObject("select count(*) from logistics.temperature_evidence where tenant_id=?::uuid and workspace_id=?::uuid",
                Integer.class, tenantId(), workspaceId());

        record(subject.token(), keyPrefix + "-missing-unit",
                        "{\"subjectType\":\"LOT\",\"subjectId\":\"" + subject.lotId()
                                + "\",\"value\":4.25,\"occurredAt\":\"" + OCCURRED_AT + "\"}")
                .andExpect(status().isBadRequest());
        record(subject.token(), keyPrefix + "-missing-subject",
                        "{\"value\":4.25,\"unit\":\"CELSIUS\",\"occurredAt\":\"" + OCCURRED_AT + "\"}")
                .andExpect(status().isBadRequest());
        record(subject.token(), keyPrefix + "-missing-time",
                        "{\"subjectType\":\"LOT\",\"subjectId\":\"" + subject.lotId()
                                + "\",\"value\":4.25,\"unit\":\"CELSIUS\"}")
                .andExpect(status().isBadRequest());

        UUID foreignWarehouse = createForeignWarehouse();
        record(subject.token(), keyPrefix + "-foreign",
                        body("WAREHOUSE", foreignWarehouse, new BigDecimal("4.25"), "CELSIUS", OCCURRED_AT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where tenant_id=?::uuid and workspace_id=?::uuid",
                Integer.class, tenantId(), workspaceId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_command_idempotency where idempotency_key like ?",
                Integer.class, keyPrefix + "%")).isZero();
    }

    @Test
    void requiresCurrentWarehouseAccessGrantForEvidenceSubject() throws Exception {
        ensureCommercialInventory();
        TemperatureSubject subject = createTemperatureSubject();
        UUID ungrantedWarehouse = createUngrantWarehouse(subject.token());
        record(subject.token(), "temperature-no-grant-" + uuid(),
                        body("WAREHOUSE", ungrantedWarehouse, new BigDecimal("5.5"), "CELSIUS", OCCURRED_AT))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where subject_id=?",
                Integer.class, ungrantedWarehouse)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions record(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/temperature-evidence")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private TemperatureSubject createTemperatureSubject() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-TEMP-" + suffix + "\",\"name\":\"Temperature evidence warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID warehouseId = UUID.fromString(json(createdWarehouse).get("id").asText());
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        MvcResult createdZone = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-TEMP-" + suffix + "\",\"name\":\"Temperature evidence zone\",\"type\":\"CHILLED\",\"temperatureMin\":2,\"temperatureMax\":8}"))
                .andExpect(status().isCreated()).andReturn();
        UUID zoneId = UUID.fromString(json(createdZone).get("id").asText());
        String batch = "TEMP-" + suffix;
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", "temperature-inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"" + batch
                                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":10,\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
        UUID lotId = jdbc.queryForObject("select id from warehouse.inventory_lot where tenant_id=?::uuid and workspace_id=?::uuid and warehouse_id=? and batch_number=?",
                UUID.class, tenantId(), workspaceId(), warehouseId, batch);
        return new TemperatureSubject(warehouseId, zoneId, lotId, warehouseToken);
    }

    private UUID createForeignWarehouse() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant now = Instant.now();
        String slug = "temperature-foreign-" + tenantId.toString().replace("-", "").substring(0, 12);
        jdbc.update("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                tenantId, "Foreign temperature tenant", slug, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                workspaceId, tenantId, "Foreign temperature workspace", slug, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into warehouse.warehouse(id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) values (?,?,?,?,?,?,'ACTIVE',?,?)",
                warehouseId, tenantId, workspaceId, "WH-" + slug.substring(slug.length() - 8),
                "Foreign warehouse", "Lima", Timestamp.from(now), Timestamp.from(now));
        return warehouseId;
    }

    private UUID createUngrantWarehouse(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult created = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-NOGRANT-" + suffix + "\",\"name\":\"Ungrant warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(created).get("id").asText());
    }

    private static String body(String subjectType, UUID subjectId, BigDecimal value, String unit, Instant occurredAt) {
        return "{\"subjectType\":\"" + subjectType + "\",\"subjectId\":\"" + subjectId
                + "\",\"value\":" + value.toPlainString() + ",\"unit\":\"" + unit
                + "\",\"occurredAt\":\"" + occurredAt + "\"}";
    }

    private record TemperatureSubject(UUID warehouseId, UUID zoneId, UUID lotId, String token) { }
}
