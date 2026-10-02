package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TemperatureEvidenceApiIT extends NexaWorkflowIntegrationSupport {
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-30T11:22:33Z");

    @Test
    void recordsPartialLotHoldsWarehouseExceptionsAndAuthorizedDispositionWithReplay() throws Exception {
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
        int wasteMovementsBefore = jdbc.queryForObject(
                "select count(*) from warehouse.stock_movement where lot_id=? and movement_type='WASTE'",
                Integer.class, subject.lotId());
        String evidenceObjectId = uploadWarehousePhoto(subject.warehouseId(), "temperature-lot-photo-" + uuid());

        String key = "temperature-evidence-" + uuid();
        BigDecimal exactValue = new BigDecimal("10.123456789");
        String reason = "Cold-chain excursion recorded for affected quantity";
        String lotBody = body("LOT", subject.lotId(), exactValue, "CELSIUS", OCCURRED_AT, evidenceObjectId,
                versionBefore, new BigDecimal("5"), reason, null);
        MvcResult created = record(subject.token(), key, lotBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("LOT"))
                .andExpect(jsonPath("$.subjectId").value(subject.lotId().toString()))
                .andExpect(jsonPath("$.warehouseId").value(subject.warehouseId().toString()))
                .andExpect(jsonPath("$.unit").value("CELSIUS"))
                .andExpect(jsonPath("$.status").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.reason").value(reason))
                .andExpect(jsonPath("$.evidenceObjectId").value(evidenceObjectId))
                .andExpect(jsonPath("$.affectedQuantity").value(5))
                .andExpect(jsonPath("$.remainingHeldQuantity").value(5))
                .andExpect(jsonPath("$.disposition").value("HOLD"))
                .andExpect(jsonPath("$.evaluationStatus").value("OPEN"))
                .andExpect(jsonPath("$.inventoryLotStatus").value("AVAILABLE"))
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

        MvcResult lotSnapshot = mockMvc.perform(get("/api/v1/inventory/lots/" + subject.lotId())
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(5))
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andReturn();
        long heldVersion = json(lotSnapshot).get("version").asLong();
        assertThat(heldVersion).isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=? and status='OPEN' and source_type='STOCK_EVIDENCE' and affected_quantity=5 and evidence_object_id=?",
                Integer.class, subject.lotId(), UUID.fromString(evidenceObjectId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select blocks_committed_execution from warehouse.inventory_temperature_evaluation where id=?",
                Boolean.class, UUID.fromString(json(created).get("inventoryTemperatureEvaluationId").asText()))).isFalse();

        String dispositionPath = "/api/v1/inventory/lots/" + subject.lotId() + "/dispositions";
        String temperatureEvaluationId = json(created).get("inventoryTemperatureEvaluationId").asText();
        String releaseBody = "{\"disposition\":\"RELEASE\",\"reason\":\"Authorized partial release\","
                + "\"affectedQuantity\":2,\"temperatureEvaluationId\":\"" + temperatureEvaluationId + "\"}";
        String releaseKey = "temperature-partial-release-" + uuid();
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + accessToken(BUYER_EMAIL, "PORTAL"))
                        .header("If-Match", "\"" + heldVersion + "\"")
                        .header("Idempotency-Key", "temperature-unauthorized-disposition-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + heldVersion + "\"")
                        .header("Idempotency-Key", releaseKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.version").value(heldVersion + 1));
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + heldVersion + "\"")
                        .header("Idempotency-Key", releaseKey)
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.version").value(heldVersion + 1));
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + heldVersion + "\"")
                        .header("Idempotency-Key", releaseKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody.replace("\"affectedQuantity\":2", "\"affectedQuantity\":1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where id=?",
                String.class, UUID.fromString(temperatureEvaluationId)))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select sum(quantity) from warehouse.inventory_lot_disposition where temperature_evaluation_id=?",
                BigDecimal.class, UUID.fromString(temperatureEvaluationId)))
                .isEqualByComparingTo("2");

        long dispositionVersion = heldVersion + 1;
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + dispositionVersion + "\"")
                        .header("Idempotency-Key", "temperature-partial-hold-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"HOLD\",\"reason\":\"Continue preventive hold\","
                                + "\"affectedQuantity\":1,\"temperatureEvaluationId\":\""
                                + temperatureEvaluationId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.onHand").value(10));
        dispositionVersion++;
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + dispositionVersion + "\"")
                        .header("Idempotency-Key", "temperature-partial-waste-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"WASTE\",\"reason\":\"Waste affected quantity\","
                                + "\"affectedQuantity\":1,\"temperatureEvaluationId\":\""
                                + temperatureEvaluationId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.onHand").value(9));
        dispositionVersion++;
        mockMvc.perform(post(dispositionPath)
                        .header("Authorization", "Bearer " + subject.token())
                        .header("If-Match", "\"" + dispositionVersion + "\"")
                        .header("Idempotency-Key", "temperature-partial-return-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"RETURN_TO_SUPPLIER\",\"reason\":\"Return affected quantity\","
                                + "\"affectedQuantity\":1,\"temperatureEvaluationId\":\""
                                + temperatureEvaluationId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.onHand").value(8));
        dispositionVersion++;
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot_disposition where temperature_evaluation_id=? and disposition='HOLD' and quantity=1",
                Integer.class, UUID.fromString(temperatureEvaluationId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=? and movement_type='WASTE'",
                Integer.class, subject.lotId())).isEqualTo(wasteMovementsBefore + 2);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=? and movement_type='WASTE' "
                        + "and actor_membership_id=? and quantity=1 and ((reason='Waste affected quantity' and quantity_before=10 and quantity_after=9) "
                        + "or (reason='Return affected quantity' and quantity_before=9 and quantity_after=8))",
                Integer.class, subject.lotId(), UUID.fromString(membershipId(WAREHOUSE_EMAIL)))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot_disposition where temperature_evaluation_id=? and disposition='RETURN_TO_SUPPLIER' and quantity=1",
                Integer.class, UUID.fromString(temperatureEvaluationId))).isEqualTo(1);
        mockMvc.perform(get("/api/v1/temperature-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedQuantity").value(5))
                .andExpect(jsonPath("$.remainingHeldQuantity").value(1));

        record(subject.token(), "temperature-stale-hold-" + uuid(),
                        body("LOT", subject.lotId(), exactValue, "CELSIUS", OCCURRED_AT, evidenceObjectId,
                                versionBefore, BigDecimal.ONE, "Stale version must not hold stock", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where tenant_id=?::uuid "
                        + "and workspace_id=?::uuid and subject_type='LOT' and subject_id=?",
                Integer.class, tenantId(), workspaceId(), subject.lotId())).isEqualTo(1);

        String warehousePhoto = uploadWarehousePhoto(subject.warehouseId(), "temperature-warehouse-photo-" + uuid());
        String warehouseKey = "temperature-evidence-" + uuid();
        MvcResult warehouseEvidence = record(subject.token(), warehouseKey,
                        body("WAREHOUSE", subject.warehouseId(), new BigDecimal("-18.7654321"), "CELSIUS", OCCURRED_AT,
                                warehousePhoto, null, null, "Warehouse excursion awaiting lot selection", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("WAREHOUSE"))
                .andExpect(jsonPath("$.subjectId").value(subject.warehouseId().toString()))
                .andExpect(jsonPath("$.status").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.exceptionStatus").value("OPEN"))
                .andExpect(jsonPath("$.selections").isEmpty())
                .andReturn();
        UUID warehouseEvidenceId = UUID.fromString(json(warehouseEvidence).get("id").asText());
        UUID exceptionId = UUID.fromString(json(warehouseEvidence).get("exceptionId").asText());
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=?",
                Integer.class, subject.lotId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.stock_temperature_exception where id=? and status='OPEN'",
                Integer.class, exceptionId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/inventory/lots/" + subject.lotId())
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.version").value(dispositionVersion));

        long currentLotVersion = jdbc.queryForObject("select version from warehouse.inventory_lot where id=?",
                Long.class, subject.lotId());
        MvcResult selection = record(subject.token(), "temperature-selection-" + uuid(),
                        body("LOT", subject.lotId(), exactValue, "CELSIUS", OCCURRED_AT, null,
                                currentLotVersion, new BigDecimal("2"), "Selected affected quantity", warehouseEvidenceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceEvidenceId").value(warehouseEvidenceId.toString()))
                .andExpect(jsonPath("$.affectedQuantity").value(2))
                .andExpect(jsonPath("$.remainingHeldQuantity").value(2))
                .andReturn();
        assertThat(json(selection).get("evidenceObjectId").asText()).isEqualTo(warehousePhoto);
        assertThat(jdbc.queryForObject("select blocks_committed_execution from warehouse.inventory_temperature_evaluation where id=?",
                Boolean.class, UUID.fromString(json(selection).get("inventoryTemperatureEvaluationId").asText()))).isFalse();
        MvcResult selectedLot = mockMvc.perform(get("/api/v1/inventory/lots/" + subject.lotId())
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(5))
                .andReturn();
        assertThat(jdbc.queryForObject("select status from logistics.stock_temperature_exception where id=?",
                String.class, exceptionId)).isEqualTo("OPEN");

        mockMvc.perform(get("/api/v1/temperature-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(evidenceId.toString()))
                .andExpect(jsonPath("$.affectedQuantity").value(5))
                .andExpect(jsonPath("$.remainingHeldQuantity").value(1))
                .andExpect(jsonPath("$.resultingLotVersion").value(heldVersion));
        mockMvc.perform(get("/api/v1/temperature-evidence/" + warehouseEvidenceId)
                        .header("Authorization", "Bearer " + subject.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selections[0].affectedQuantity").value(2))
                .andExpect(jsonPath("$.selections[0].remainingHeldQuantity").value(2));

        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where id=?", Integer.class, evidenceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.temperature_evidence where id=?", Integer.class, warehouseEvidenceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_command_idempotency where operation='STOCK_TEMPERATURE_EVIDENCE' and idempotency_key in (?,?)",
                Integer.class, key, warehouseKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where event_type='TemperatureEvidenceRecorded.v1' and aggregate_id in (?,?)",
                Integer.class, evidenceId, warehouseEvidenceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select stock_quantity from warehouse.inventory_lot where id=?", BigDecimal.class, subject.lotId()))
                .isEqualByComparingTo(stockBefore.subtract(new BigDecimal("2")));
        assertThat(jdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot where id=?", BigDecimal.class, subject.lotId()))
                .isEqualByComparingTo(reservedBefore);
        assertThat(jdbc.queryForObject("select version from warehouse.inventory_lot where id=?", Long.class, subject.lotId()))
                .isEqualTo(json(selectedLot).get("version").asLong());
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where lot_id=?", Integer.class, subject.lotId()))
                .isEqualTo(movementsBefore + 2);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_temperature_evaluation where lot_id=?", Integer.class,
                subject.lotId())).isEqualTo(2);
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
        record(subject.token(), keyPrefix + "-out-of-range-without-evidence",
                        body("LOT", subject.lotId(), new BigDecimal("10.5"), "CELSIUS", OCCURRED_AT,
                                null, null, null, "Missing photo", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        String photo = uploadWarehousePhoto(subject.warehouseId(), keyPrefix + "-precision-photo");
        long lotVersion = jdbc.queryForObject("select version from warehouse.inventory_lot where id=?",
                Long.class, subject.lotId());
        record(subject.token(), keyPrefix + "-quantity-overflow",
                        body("LOT", subject.lotId(), new BigDecimal("10.5"), "CELSIUS", OCCURRED_AT,
                                photo, lotVersion, new BigDecimal("1000000000000000"), "Overflow must fail", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        record(subject.token(), keyPrefix + "-quantity-scale",
                        body("LOT", subject.lotId(), new BigDecimal("10.5"), "CELSIUS", OCCURRED_AT,
                                photo, lotVersion, new BigDecimal("1.00001"), "Scale must fail", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

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
    void marksLotTemperatureHoldAsExecutionBlockingWhenReservedQuantityIsAffected() throws Exception {
        ensureCommercialInventory();
        TemperatureSubject subject = createTemperatureSubject("2098-01-01", "CAT-0003");
        PurchaseRequestResource purchaseRequest = createApprovedPurchaseRequestForItem("CAT-0003");
        SalesOrderResource order = convert(purchaseRequest, "thermal-reserve-convert-" + uuid());
        MvcResult confirmed = mockMvc.perform(post("/api/v1/sales-orders/" + order.id() + "/confirmations")
                        .header("Authorization", "Bearer " + order.salesToken())
                        .header("If-Match", order.etag()))
                .andExpect(status().isOk()).andReturn();
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult reservation = mockMvc.perform(post("/api/v1/fulfillment-candidates/" + order.id() + "/inventory-reservations")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", confirmed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "thermal-reserve-" + uuid()))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(reservation).get("allocations").get(0).get("lotId").asText())
                .isEqualTo(subject.lotId().toString());

        String photo = uploadWarehousePhoto(subject.warehouseId(), "thermal-reserved-photo-" + uuid());
        long lotVersion = jdbc.queryForObject("select version from warehouse.inventory_lot where id=?",
                Long.class, subject.lotId());
        MvcResult evidence = record(warehouseToken, "thermal-reserved-evidence-" + uuid(),
                        body("LOT", subject.lotId(), new BigDecimal("10.5"), "CELSIUS", OCCURRED_AT,
                                photo, lotVersion, new BigDecimal("10"), "Excursion affects reserved stock", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affectedQuantity").value(10))
                .andReturn();
        UUID evaluationId = UUID.fromString(json(evidence).get("inventoryTemperatureEvaluationId").asText());
        assertThat(jdbc.queryForObject("select blocks_committed_execution from warehouse.inventory_temperature_evaluation where id=?",
                Boolean.class, evaluationId)).isTrue();
        mockMvc.perform(get("/api/v1/inventory/lots/" + subject.lotId())
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.reserved").value(1))
                .andExpect(jsonPath("$.available").value(0));
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
        return createTemperatureSubject("2099-01-01");
    }

    private TemperatureSubject createTemperatureSubject(String expirationDate) throws Exception {
        return createTemperatureSubject(expirationDate, "CAT-0002");
    }

    private TemperatureSubject createTemperatureSubject(String expirationDate, String catalogItemId) throws Exception {
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
                                + "\",\"catalogItemId\":\"" + catalogItemId + "\",\"batchNumber\":\"" + batch
                                + "\",\"expirationDate\":\"" + expirationDate + "\",\"quantity\":10,\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
        UUID lotId = jdbc.queryForObject("select id from warehouse.inventory_lot where tenant_id=?::uuid and workspace_id=?::uuid and warehouse_id=? and batch_number=?",
                UUID.class, tenantId(), workspaceId(), warehouseId, batch);
        return new TemperatureSubject(warehouseId, zoneId, lotId, warehouseToken);
    }

    private PurchaseRequestResource createApprovedPurchaseRequestForItem(String catalogItemId) throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"catalogItemId\":\"" + catalogItemId
                                + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String requestId = json(created).get("id").asText();
        MvcResult submitted = mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "temperature-submit-" + uuid()))
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

    private String uploadWarehousePhoto(UUID warehouseId, String key) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(new MockMultipartFile("file", "temperature-photo.png", "image/png",
                                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/g5sAAAAASUVORK5CYII=")))
                        .param("subjectType", "WAREHOUSE").param("subjectId", warehouseId.toString())
                        .header("Authorization", "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM"))
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return jsonText(response, "id");
    }

    private static String body(String subjectType, UUID subjectId, BigDecimal value, String unit, Instant occurredAt,
                               String evidenceObjectId, Long expectedLotVersion, BigDecimal affectedQuantity,
                               String reason, UUID sourceEvidenceId) {
        StringBuilder result = new StringBuilder("{\"subjectType\":\"").append(subjectType)
                .append("\",\"subjectId\":\"").append(subjectId).append("\",\"value\":")
                .append(value.toPlainString()).append(",\"unit\":\"").append(unit)
                .append("\",\"occurredAt\":\"").append(occurredAt).append("\"");
        if (evidenceObjectId != null) result.append(",\"evidenceObjectId\":\"").append(evidenceObjectId).append("\"");
        if (expectedLotVersion != null) result.append(",\"expectedLotVersion\":").append(expectedLotVersion);
        if (affectedQuantity != null) result.append(",\"affectedQuantity\":").append(affectedQuantity.toPlainString());
        if (reason != null) result.append(",\"reason\":\"").append(reason).append("\"");
        if (sourceEvidenceId != null) result.append(",\"sourceEvidenceId\":\"").append(sourceEvidenceId).append("\"");
        return result.append('}').toString();
    }

    private static String body(String subjectType, UUID subjectId, BigDecimal value, String unit, Instant occurredAt) {
        return body(subjectType, subjectId, value, unit, occurredAt, null, null, null, null, null);
    }

    private String jsonText(String response, String field) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(response).get(field).asText();
    }

    private record TemperatureSubject(UUID warehouseId, UUID zoneId, UUID lotId, String token) { }
}
