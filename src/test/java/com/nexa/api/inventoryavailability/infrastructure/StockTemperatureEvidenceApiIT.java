package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class StockTemperatureEvidenceApiIT extends PostgresIntegrationSupport {
    private static final String OBSERVED_AT = "2026-09-25T12:34:56Z";

    @Test
    void recordsLotAndWarehouseEvidenceWithActorTimeUnitAndSafeRetry() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, suffix);
        String zoneId = createZone(token, warehouseId, suffix, null, null);
        String lotId = receiveLot(token, warehouseId, zoneId, suffix, null);
        String actorId = membershipId(WAREHOUSE_EMAIL);

        String lotBody = "{\"lotId\":\"" + lotId + "\",\"value\":4.25,\"unit\":\"CELSIUS\",\"observedAt\":\"" + OBSERVED_AT + "\"}";
        MvcResult lotResult = record(token, "stock-lot-" + suffix, lotBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("LOT"))
                .andExpect(jsonPath("$.lotId").value(lotId))
                .andExpect(jsonPath("$.warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.value").value(4.25))
                .andExpect(jsonPath("$.unit").value("CELSIUS"))
                .andExpect(jsonPath("$.observedAt").value(OBSERVED_AT))
                .andExpect(jsonPath("$.actorMembershipId").value(actorId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recordedAt").isNotEmpty())
                .andReturn();
        String lotEvidenceId = json(lotResult).get("id").asText();

        MvcResult replay = record(token, "stock-lot-" + suffix, lotBody)
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(lotEvidenceId);
        record(token, "stock-lot-" + suffix,
                "{\"lotId\":\"" + lotId + "\",\"value\":4.5,\"unit\":\"CELSIUS\",\"observedAt\":\"" + OBSERVED_AT + "\"}")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_temperature_evidence where idempotency_key=?",
                Integer.class, "stock-lot-" + suffix)).isEqualTo(1);

        String warehouseBody = "{\"warehouseId\":\"" + warehouseId + "\",\"value\":38.5,\"unit\":\"FAHRENHEIT\",\"observedAt\":\"" + OBSERVED_AT + "\"}";
        record(token, "stock-warehouse-" + suffix, warehouseBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("WAREHOUSE"))
                .andExpect(jsonPath("$.lotId").doesNotExist())
                .andExpect(jsonPath("$.warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.unit").value("FAHRENHEIT"))
                .andExpect(jsonPath("$.actorMembershipId").value(actorId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsMissingSubjectOrUnitWithoutCreatingEvidence() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, suffix);
        String actorId = membershipId(WAREHOUSE_EMAIL);
        int before = jdbc.queryForObject("select count(*) from warehouse.stock_temperature_evidence where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));

        record(token, "missing-subject-" + suffix,
                "{\"value\":4.25,\"unit\":\"CELSIUS\",\"observedAt\":\"" + OBSERVED_AT + "\"}")
                .andExpect(status().isBadRequest());
        record(token, "missing-unit-" + suffix,
                "{\"warehouseId\":\"" + warehouseId + "\",\"value\":4.25,\"observedAt\":\"" + OBSERVED_AT + "\"}")
                .andExpect(status().isBadRequest());
        record(token, "both-subjects-" + suffix,
                "{\"lotId\":\"" + uuid() + "\",\"warehouseId\":\"" + warehouseId
                        + "\",\"value\":4.25,\"unit\":\"CELSIUS\",\"observedAt\":\"" + OBSERVED_AT + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_temperature_evidence where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_temperature_evidence where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key in (?,?,?)",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), UUID.fromString(actorId),
                "missing-subject-" + suffix, "missing-unit-" + suffix, "both-subjects-" + suffix)).isZero();
    }

    @Test
    void outOfRangeReadingStaysPendingAndDoesNotReleaseOrMutateHeldStock() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = suffix();
        String warehouseId = createWarehouse(token, suffix);
        String zoneId = createZone(token, warehouseId, suffix, -5, 5);
        String lotId = receiveLot(token, warehouseId, zoneId, suffix, 10);
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_lot where id=?",
                String.class, UUID.fromString(lotId))).isEqualTo("HOLD");

        record(token, "stock-out-of-range-" + suffix,
                "{\"lotId\":\"" + lotId + "\",\"value\":10,\"unit\":\"CELSIUS\",\"observedAt\":\"" + OBSERVED_AT + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_temperature_evidence where lot_id=? and status='PENDING'",
                Integer.class, UUID.fromString(lotId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_lot where id=?",
                String.class, UUID.fromString(lotId))).isEqualTo("HOLD");
        assertThat(jdbc.queryForObject("select status from warehouse.inventory_temperature_evaluation where lot_id=?",
                String.class, UUID.fromString(lotId))).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_lot_disposition where lot_id=?",
                Integer.class, UUID.fromString(lotId))).isZero();
    }

    @Test
    void restrictedRuntimeCanInsertWithinScopeButCannotReadOtherScopeOrMutateEvidence() throws Exception {
        String token = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String warehouseId = createWarehouse(token, suffix());
        UUID evidenceId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID warehouse = UUID.fromString(warehouseId);
        UUID actor = UUID.fromString(membershipId(WAREHOUSE_EMAIL));
        String key = "rls-" + evidenceId;

        try (Connection connection = openRuntimeConnection()) {
            setScope(connection, tenant, workspace);
            insertWarehouseEvidence(connection, evidenceId, tenant, workspace, warehouse, actor, key);
            assertThat(count(connection, "select count(*) from warehouse.stock_temperature_evidence where id=?", evidenceId)).isEqualTo(1);

            setScope(connection, UUID.randomUUID(), workspace);
            assertThat(count(connection, "select count(*) from warehouse.stock_temperature_evidence where id=?", evidenceId))
                    .as("a mismatched tenant cannot read the evidence")
                    .isZero();
            setScope(connection, tenant, UUID.randomUUID());
            assertThat(count(connection, "select count(*) from warehouse.stock_temperature_evidence where id=?", evidenceId))
                    .as("a mismatched workspace cannot read the evidence")
                    .isZero();

            setScope(connection, tenant, workspace);
            assertThat(sqlState(connection, "insert into warehouse.stock_temperature_evidence "
                    + "(id,tenant_id,workspace_id,subject_type,warehouse_id,value,unit,observed_at,recorded_at,actor_membership_id,status,idempotency_key,request_hash) "
                    + "values ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "','" + workspace + "','WAREHOUSE','" + warehouse
                    + "',1,'CELSIUS',current_timestamp,current_timestamp,'" + actor + "','PENDING','" + key + "-wrong-scope','"
                    + "0".repeat(64) + "')")).isEqualTo("42501");
            assertThat(sqlState(connection, "insert into warehouse.stock_temperature_evidence "
                    + "(id,tenant_id,workspace_id,subject_type,warehouse_id,value,unit,observed_at,recorded_at,actor_membership_id,status,idempotency_key,request_hash) "
                    + "values ('" + UUID.randomUUID() + "','" + tenant + "','" + UUID.randomUUID() + "','WAREHOUSE','" + warehouse
                    + "',1,'CELSIUS',current_timestamp,current_timestamp,'" + actor + "','PENDING','" + key + "-wrong-workspace','"
                    + "0".repeat(64) + "')")).isEqualTo("42501");
            assertThat(sqlState(connection, "update warehouse.stock_temperature_evidence set status='PENDING' where id='" + evidenceId + "'"))
                    .as("runtime has no UPDATE privilege on append-only evidence")
                    .isEqualTo("42501");
            assertThat(sqlState(connection, "delete from warehouse.stock_temperature_evidence where id='" + evidenceId + "'"))
                    .as("runtime has no DELETE privilege on append-only evidence")
                    .isEqualTo("42501");
            assertThat(hasPrivilege(connection, "TRUNCATE")).isFalse();

            clearScope(connection);
            assertThat(count(connection, "select count(*) from warehouse.stock_temperature_evidence where id=?", evidenceId))
                    .as("missing scope fails closed")
                    .isZero();
        }
    }

    private org.springframework.test.web.servlet.ResultActions record(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/temperature-evidence")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String createWarehouse(String token, String suffix) throws Exception {
        String response = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TE-" + suffix + "\",\"name\":\"Evidence Warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).get("id").asText();
    }

    private String createZone(String token, String warehouseId, String suffix, Integer min, Integer max) throws Exception {
        String bounds = min == null ? "" : ",\"temperatureMin\":" + min + ",\"temperatureMax\":" + max;
        String response = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"T-" + suffix + "\",\"name\":\"Evidence Zone\",\"type\":\"CHILLED\"" + bounds + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).get("id").asText();
    }

    private String receiveLot(String token, String warehouseId, String zoneId, String suffix, Integer temperature) throws Exception {
        String temperatureJson = temperature == null ? "" : ",\"temperatureReading\":" + temperature;
        String response = mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "evidence-receipt-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"TE-" + suffix
                                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":\"10\",\"unit\":\"UNIT\""
                                + temperatureJson + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).get("id").asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private static tools.jackson.databind.JsonNode json(String result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result);
    }

    private static void insertWarehouseEvidence(Connection connection, UUID id, UUID tenant, UUID workspace,
                                                UUID warehouse, UUID actor, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("insert into warehouse.stock_temperature_evidence "
                + "(id,tenant_id,workspace_id,subject_type,warehouse_id,value,unit,observed_at,recorded_at,actor_membership_id,status,idempotency_key,request_hash) "
                + "values (?,?,?,'WAREHOUSE',?,3.5,'CELSIUS',current_timestamp,current_timestamp,?,'PENDING',?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, tenant);
            statement.setObject(3, workspace);
            statement.setObject(4, warehouse);
            statement.setObject(5, actor);
            statement.setString(6, key);
            statement.setString(7, "0".repeat(64));
            statement.executeUpdate();
        }
    }

    private static void setScope(Connection connection, UUID tenant, UUID workspace) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('app.current_tenant_id', ?, false), set_config('app.current_workspace_id', ?, false)")) {
            statement.setString(1, tenant.toString());
            statement.setString(2, workspace.toString());
            statement.execute();
        }
    }

    private static void clearScope(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("select set_config('app.current_tenant_id', '', false), set_config('app.current_workspace_id', '', false)");
        }
    }

    private static int count(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static boolean hasPrivilege(Connection connection, String privilege) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select has_table_privilege(current_user, 'warehouse.stock_temperature_evidence', ?)")) {
            statement.setString(1, privilege);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static String sqlState(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            return null;
        } catch (SQLException exception) {
            return exception.getSQLState();
        }
    }
}
