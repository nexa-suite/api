package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Tenant persistence with a synthetic delivery and simulated GPS samples. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DriverLiveLocationIT extends NexaWorkflowIntegrationSupport {
    private SyntheticWorkspaceIdentity driverIdentity;

    @AfterEach
    void retireSyntheticDriver() {
        if (driverIdentity != null) retireSyntheticWorkspaceIdentity(driverIdentity);
    }

    @Test
    void locationIsVisibleOnlyForTheAuthorizedActiveDelivery() throws Exception {
        driverIdentity = createSyntheticWorkspaceIdentity(LOGISTICS_EMAIL, "logistics", "GPS Driver");
        var dispatch = createReservedDispatch();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID driver = driverIdentity.membershipId();
        UUID delivery = UUID.randomUUID();
        Instant dispatchStart = Instant.now().minusSeconds(2);
        tenantJdbc().update("insert into logistics.delivery(id,tenant_id,workspace_id,dispatch_order_id,status,destination_snapshot,dispatched_at,created_at,updated_at) values(?,?,?,?,'DISPATCHED','Synthetic GPS destination',?,?,?)",
                delivery, tenant, workspace, UUID.fromString(dispatch.id()), Timestamp.from(dispatchStart), Timestamp.from(dispatchStart), Timestamp.from(dispatchStart));
        tenantJdbc().update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,responsible_membership_id,operator_id,assigned_at,actor_membership_id) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, delivery, driver, UUID.randomUUID(), Timestamp.from(dispatchStart), driver);
        String driverAuth = "Bearer " + accessToken(driverIdentity.email(), "PLATFORM");
        String buyerAuth = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        var started = mockMvc.perform(post("/api/v1/driver/workdays")
                        .header("Authorization", driverAuth).header("Idempotency-Key", "gps-start-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locationAvailable").value(false)).andReturn();
        String workday = json(started).get("id").asText();
        UUID sample = UUID.randomUUID();
        String body = "{\"sampleId\":\"" + sample + "\",\"latitude\":-12.0464,\"longitude\":-77.0428,\"accuracyMeters\":5,\"capturedAt\":\"" + Instant.now() + "\"}";
        mockMvc.perform(post("/api/v1/driver/workdays/" + workday + "/locations")
                        .header("Authorization", driverAuth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/driver/workdays/" + workday + "/location-availability")
                        .header("Authorization", driverAuth).header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "gps-enable-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locationAvailable").value(true));
        mockMvc.perform(post("/api/v1/driver/workdays/" + workday + "/locations")
                        .header("Authorization", driverAuth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sampleId").value(sample.toString()));
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + delivery + "/live-location").header("Authorization", buyerAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.location.sampleId").value(sample.toString()));
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + UUID.randomUUID() + "/live-location").header("Authorization", buyerAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + delivery + "/live-location")
                        .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/driver/workdays/" + workday + "/locations")
                        .header("Authorization", driverAuth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.driver_coordinate where sample_id=?", Integer.class, sample)).isEqualTo(1);
        int delivered = tenantJdbc().update("update logistics.delivery set status='DELIVERED',delivered_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=?", tenant, workspace, delivery);
        assertThat(delivered).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery d "
                        + "where d.tenant_id=? and d.workspace_id=? and d.status in ('DISPATCHED','IN_TRANSIT') "
                        + "and exists (select 1 from logistics.delivery_assignment a where a.tenant_id=d.tenant_id "
                        + "and a.workspace_id=d.workspace_id and a.delivery_id=d.id and a.responsible_membership_id=?)",
                Integer.class, tenant, workspace, driver)).isZero();
        mockMvc.perform(get("/api/v1/buyer/deliveries/" + delivery + "/live-location").header("Authorization", buyerAuth))
                .andExpect(status().isNotFound());
        String lateBody = body.replace(sample.toString(), UUID.randomUUID().toString());
        mockMvc.perform(post("/api/v1/driver/workdays/" + workday + "/locations")
                        .header("Authorization", driverAuth).contentType(MediaType.APPLICATION_JSON).content(lateBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_LOCATION_NO_ACTIVE_DELIVERY"));
    }
}
