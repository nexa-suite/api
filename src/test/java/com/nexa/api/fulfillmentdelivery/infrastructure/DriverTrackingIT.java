package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {"spring.datasource.hikari.minimum-idle=1",
        "nexa.tenant-business.fulfillment-delivery.enabled=false"})
/** V1 excludes stored driver tracking; these endpoints must fail closed without persisting location data. */
class DriverTrackingIT extends NexaWorkflowIntegrationSupport {
    @Test
    void storedDriverTrackingIsUnavailableForV1AndDoesNotWrite() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID driver = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        StoredTrackingCounts centralBefore = trackingCounts(jdbc, tenant, workspace, driver);
        StoredTrackingCounts businessBefore = trackingCounts(tenantJdbc(), tenant, workspace, driver);
        String auth = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        UUID workdayId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/driver/workdays/current").header("Authorization", auth))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization", auth)
                        .header("Idempotency-Key", "tracking-v1-start-" + UUID.randomUUID())
                        .contentType("application/json").content("{\"locationAvailable\":true}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/driver/workdays/{id}/ends", workdayId).header("Authorization", auth)
                        .header("If-Match", "\"0\"").header("Idempotency-Key", "tracking-v1-end-" + UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/driver/workdays/{id}/location-availability", workdayId)
                        .header("Authorization", auth).header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "tracking-v1-availability-" + UUID.randomUUID())
                        .contentType("application/json").content("{\"locationAvailable\":false}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/driver/workdays/{id}/locations", workdayId).header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"sampleId\":\"" + UUID.randomUUID() + "\",\"latitude\":-12.1,"
                                + "\"longitude\":-77.0,\"accuracyMeters\":5,\"capturedAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(get("/api/v1/driver/location").header("Authorization", auth))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));

        assertThat(trackingCounts(jdbc, tenant, workspace, driver)).isEqualTo(centralBefore);
        assertThat(trackingCounts(tenantJdbc(), tenant, workspace, driver)).isEqualTo(businessBefore);
    }

    private StoredTrackingCounts trackingCounts(JdbcTemplate database, UUID tenant, UUID workspace, UUID driver) {
        return new StoredTrackingCounts(
                database.queryForObject("select count(*) from logistics.driver_workday where tenant_id=? and workspace_id=? and actor_membership_id=?",
                        Long.class, tenant, workspace, driver),
                database.queryForObject("select count(*) from logistics.driver_workday_event where tenant_id=? and workspace_id=? and actor_membership_id=?",
                        Long.class, tenant, workspace, driver),
                database.queryForObject("select count(*) from logistics.driver_coordinate where tenant_id=? and workspace_id=? and actor_membership_id=?",
                        Long.class, tenant, workspace, driver));
    }

    private record StoredTrackingCounts(Long workdays, Long events, Long coordinates) { }
}
