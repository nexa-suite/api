package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises driver tracking through the supported Tenant fulfillment-delivery route. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DriverTrackingIT extends NexaWorkflowIntegrationSupport {
    private SyntheticWorkspaceIdentity driverIdentity;

    @Autowired
    private TenantBusinessDatabaseCredentialsProvider tenantBusinessDatabaseCredentialsProvider;

    @AfterEach
    void retireSyntheticDriver() {
        if (driverIdentity != null) retireSyntheticWorkspaceIdentity(driverIdentity);
    }

    @Test
    void workdayClosureAndPermissionLossStopCoordinatesAndScopeRemainsPrivate() throws Exception {
        driverIdentity = createSyntheticWorkspaceIdentity(LOGISTICS_EMAIL, "logistics", "Tracking Driver");
        String token = accessToken(driverIdentity.email(), "PLATFORM");
        String auth = "Bearer " + token;
        UUID fixtureTenant = UUID.fromString(tenantId());
        UUID fixtureWorkspace = UUID.fromString(workspaceId());
        UUID fixtureDriver = driverIdentity.membershipId();
        mockMvc.perform(get("/api/v1/driver/workdays/current").header("Authorization", auth))
                .andExpect(status().isNoContent());
        String key = "tracking-start-" + UUID.randomUUID();
        var started = mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationAvailable\":true}"))
                .andExpect(status().isOk()).andReturn();
        String id = json(started).get("id").asText();
        String version = started.getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationAvailable\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.startedAt").value(json(started).get("startedAt").asText()));
        Instant capturedAt = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plusNanos(123456789);
        String sample = "{\"sampleId\":\"" + UUID.randomUUID()
                + "\",\"latitude\":-12.1,\"longitude\":-77.0,\"accuracyMeters\":5,\"capturedAt\":\""
                + capturedAt + "\"}";
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_LOCATION_NO_ACTIVE_DELIVERY"));

        var dispatch = createReservedDispatch();
        UUID fixtureDelivery = UUID.randomUUID();
        Instant dispatchStart = Instant.now().minusSeconds(60);
        tenantJdbc().update("insert into logistics.delivery(id,tenant_id,workspace_id,dispatch_order_id,status,"
                        + "destination_snapshot,dispatched_at,created_at,updated_at) "
                        + "values(?,?,?,?,'DISPATCHED','Synthetic tracking destination',?,?,?)",
                fixtureDelivery, fixtureTenant, fixtureWorkspace, UUID.fromString(dispatch.id()),
                java.sql.Timestamp.from(dispatchStart), java.sql.Timestamp.from(dispatchStart),
                java.sql.Timestamp.from(dispatchStart));
        tenantJdbc().update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,"
                        + "responsible_membership_id,operator_id,assigned_at,actor_membership_id) "
                        + "values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), fixtureTenant, fixtureWorkspace, fixtureDelivery, fixtureDriver,
                UUID.randomUUID(), java.sql.Timestamp.from(dispatchStart), fixtureDriver);

        var firstSample = mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk()).andReturn();
        var replayedSample = mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(replayedSample)).isEqualTo(json(firstSample));
        assertThat(json(firstSample).get("capturedAt").asText()).isEqualTo(
                capturedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        String changedNanoSample = sample.replace(capturedAt.toString(), capturedAt.plusNanos(1).toString());
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(changedNanoSample))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        mockMvc.perform(get("/api/v1/driver/location").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(-12.1));
        var paused = mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/location-availability")
                        .header("Authorization", auth).header("If-Match", version)
                        .header("Idempotency-Key", "pause-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCATION_UNAVAILABLE")).andReturn();
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/ends").header("Authorization", auth)
                        .header("If-Match", version).header("Idempotency-Key", "stale-end-" + UUID.randomUUID()))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/ends").header("Authorization", auth)
                        .header("If-Match", paused.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "end-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/driver/workdays/" + id + "/locations").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isConflict());
        String warehouse = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/dispatch/drivers/" + fixtureDriver + "/location")
                        .header("Authorization", warehouse))
                .andExpect(status().isForbidden());
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + UUID.randomUUID() + "/live-location")
                        .header("Authorization", buyer))
                .andExpect(status().isNotFound());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.driver_workday_event where workday_id=?",
                Integer.class, UUID.fromString(id))).isEqualTo(3);
        UUID sampleId = UUID.fromString(json(firstSample).get("sampleId").asText());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.driver_coordinate where workday_id=?",
                Integer.class, UUID.fromString(id))).isEqualTo(1);

        UUID expiredDay = UUID.randomUUID();
        UUID actor = fixtureDriver;
        tenantJdbc().update("insert into logistics.driver_workday(id,tenant_id,workspace_id,actor_membership_id,"
                        + "version,status,started_at,ended_at) values(?,?,?, ?,0,'CLOSED',"
                        + "current_timestamp-interval '26 hours',current_timestamp-interval '24 hours')",
                expiredDay, fixtureTenant, fixtureWorkspace, actor);
        UUID expiredSample = UUID.randomUUID();
        tenantJdbc().update("insert into logistics.driver_coordinate(sample_id,tenant_id,workspace_id,actor_membership_id,"
                        + "workday_id,latitude,longitude,accuracy_meters,captured_at,received_at,expires_at) "
                        + "values(?,?,?,?,?,0,0,1,current_timestamp-interval '25 hours',"
                        + "current_timestamp-interval '25 hours',current_timestamp-interval '1 hour')",
                expiredSample, fixtureTenant, fixtureWorkspace, actor, expiredDay);

        try (Connection connection = openTenantRuntimeConnection(fixtureTenant)) {
            assertThat(coordinateCount(connection, expiredSample)).isZero();
            setTenantScope(connection, fixtureTenant, fixtureWorkspace);
            assertThat(coordinateCount(connection, expiredSample)).isEqualTo(1);
            setTenantScope(connection, UUID.randomUUID(), fixtureWorkspace);
            assertThat(coordinateCount(connection, expiredSample)).isZero();
            setTenantScope(connection, fixtureTenant, fixtureWorkspace);
            try (var statement = connection.createStatement()) {
                statement.execute("select logistics.purge_expired_driver_coordinates()");
            }
        }
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.driver_coordinate where sample_id=?",
                Integer.class, expiredSample)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.driver_coordinate where sample_id=?",
                Integer.class, sampleId)).isEqualTo(1);
    }

    private Connection openTenantRuntimeConnection(UUID tenantId) throws SQLException {
        String credentialReference = jdbc.queryForObject("select credential_secret_reference "
                        + "from tenant_management.tenant_business_database_binding "
                        + "where tenant_id=? and lifecycle_state='READY'",
                String.class, tenantId);
        var credentials = tenantBusinessDatabaseCredentialsProvider.requireCredentials(credentialReference);
        return DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(), credentials.password());
    }

    private static int coordinateCount(Connection connection, UUID sampleId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select count(*) from logistics.driver_coordinate where sample_id=?")) {
            query.setObject(1, sampleId);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void setTenantScope(Connection connection, UUID tenantId, UUID workspaceId) throws SQLException {
        try (PreparedStatement scope = connection.prepareStatement(
                "select set_config('app.current_tenant_id', ?, false), "
                        + "set_config('app.current_workspace_id', ?, false)")) {
            scope.setString(1, tenantId.toString());
            scope.setString(2, workspaceId.toString());
            scope.execute();
        }
    }
}
