package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named="nexa.integration.enabled",matches="true")
@TestPropertySource(properties="spring.datasource.hikari.minimum-idle=1")
class DriverTrackingIT extends NexaWorkflowIntegrationSupport {
    @Test void workdayClosureAndPermissionLossStopCoordinatesAndScopeRemainsPrivate() throws Exception {
        String token = accessToken(LOGISTICS_EMAIL,"PLATFORM");
        String auth = "Bearer " + token;
        var priorDay = mockMvc.perform(get("/api/v1/driver/workdays/current").header("Authorization",auth)).andReturn();
        if (priorDay.getResponse().getStatus() == 200) {
            mockMvc.perform(post("/api/v1/driver/workdays/"+json(priorDay).get("id").asText()+"/ends")
                    .header("Authorization",auth).header("If-Match",priorDay.getResponse().getHeader("ETag"))
                    .header("Idempotency-Key","tracking-fixture-end-"+UUID.randomUUID())).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/driver/workdays/current").header("Authorization",auth)).andExpect(status().isNoContent());
        String key = "tracking-start-" + UUID.randomUUID();
        var started = mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization",auth)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationAvailable\":true}")).andExpect(status().isOk()).andReturn();
        String id = json(started).get("id").asText();
        String version = started.getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization",auth)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationAvailable\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        String sample = "{\"sampleId\":\""+UUID.randomUUID()+"\",\"latitude\":-12.1,\"longitude\":-77.0,\"accuracyMeters\":5,\"capturedAt\":\""+Instant.now()+"\"}";
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/locations").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(sample)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/locations").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(sample)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/driver/location").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(-12.1));
        var paused = mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/location-availability").header("Authorization",auth)
                .header("If-Match",version).header("Idempotency-Key","pause-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCATION_UNAVAILABLE")).andReturn();
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/locations").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(sample)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/ends").header("Authorization",auth)
                .header("If-Match",version).header("Idempotency-Key","stale-end-"+UUID.randomUUID())).andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/ends").header("Authorization",auth)
                .header("If-Match",paused.getResponse().getHeader("ETag")).header("Idempotency-Key","end-"+UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/driver/workdays/"+id+"/locations").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(sample)).andExpect(status().isConflict());
        String warehouse = "Bearer " + accessToken(WAREHOUSE_EMAIL,"PLATFORM");
        mockMvc.perform(get("/api/v1/dispatch/drivers/"+membershipId(LOGISTICS_EMAIL)+"/location").header("Authorization",warehouse)).andExpect(status().isForbidden());
        String buyer = "Bearer " + accessToken(BUYER_EMAIL,"PORTAL");
        mockMvc.perform(get("/api/v1/buyer/deliveries/"+UUID.randomUUID()+"/live-location").header("Authorization",buyer)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_workday_event where workday_id=?",Integer.class,UUID.fromString(id))).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_coordinate where workday_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        UUID expiredDay = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID actor = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        jdbc.update("insert into logistics.driver_workday(id,tenant_id,workspace_id,actor_membership_id,version,status,started_at,ended_at) values(?,?,?,?,0,'CLOSED',current_timestamp-interval '26 hours',current_timestamp-interval '24 hours')", expiredDay,tenant,workspace,actor);
        UUID expiredSample = UUID.randomUUID();
        jdbc.update("insert into logistics.driver_coordinate(sample_id,tenant_id,workspace_id,actor_membership_id,workday_id,latitude,longitude,accuracy_meters,captured_at,received_at,expires_at) values(?,?,?,?,?,0,0,1,current_timestamp-interval '25 hours',current_timestamp-interval '25 hours',current_timestamp-interval '1 hour')", expiredSample,tenant,workspace,actor,expiredDay);
        try (var connection = com.nexa.api.support.PostgresIntegrationSupport.openRuntimeConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select count(*) from logistics.driver_coordinate where sample_id='"+expiredSample+"'")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            statement.execute("select set_config('app.current_tenant_id','"+tenant+"',false),set_config('app.current_workspace_id','"+workspace+"',false)");
            try (var rows = statement.executeQuery("select count(*) from logistics.driver_coordinate where sample_id='"+expiredSample+"'")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            statement.execute("select set_config('app.current_tenant_id','"+UUID.randomUUID()+"',false)");
            try (var rows = statement.executeQuery("select count(*) from logistics.driver_coordinate where sample_id='"+expiredSample+"'")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            statement.execute("select logistics.purge_expired_driver_coordinates()");
        }
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_coordinate where sample_id=?",Integer.class,expiredSample)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_coordinate where workday_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }
}
