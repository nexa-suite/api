package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL/HTTP coverage for Business Operations Manager coordination and authority boundaries. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.buyer-wallet-read.enabled=true",
        "nexa.tenant-business.buyer-wallet-recharge.enabled=true",
        "nexa.tenant-business.purchase-request.enabled=true",
        "nexa.tenant-business.catalog-detail-read.enabled=true",
        "nexa.tenant-business.catalog-management.enabled=true",
        "nexa.tenant-business.payments.enabled=true",
        "nexa.tenant-business.fulfillment-delivery.enabled=true",
        "nexa.tenant-business.warehouse-operations.enabled=true",
        "nexa.tenant-business.local-fixtures.enabled=true",
        "nexa.tenant-business.support-sales-order-read.enabled=true",
        "nexa.tenant-business.business-documents.enabled=true",
        "nexa.tenant-business.business-traceability.enabled=true",
        "nexa.tenant-business.notifications.enabled=true",
        "nexa.business-traceability.worker-delay-ms=3600000",
        "spring.datasource.hikari.minimum-idle=1"
})
class BomOperationalExceptionIT extends NexaWorkflowIntegrationSupport {

    @Test
    void coordinatesSourceLinkedCaseWithCurrentTargetsAndCannotResolveBlockingFacts() throws Exception {
        ensureCommercialInventory();
        String actorId = membershipId(LOGISTICS_EMAIL);
        jdbc.update("insert into tenant_management.membership_role_definition(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id) "
                        + "select ?,w.tenant_id,w.id,r.id,? from tenant_management.workspace w "
                        + "join tenant_management.role_definition r on r.code='business_operations_manager' "
                        + "and r.tenant_id is null and r.workspace_id is null where w.slug=? "
                        + "on conflict(membership_id,role_id) do nothing",
                UUID.fromString(actorId), UUID.fromString(actorId), WORKSPACE_SLUG);
        ActiveDelivery delivery = createActiveDelivery();
        String managerToken = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String incidentPath = "/api/v1/driver/deliveries/" + delivery.deliveryId() + "/attempts/"
                + delivery.attemptId() + "/incidents";
        MvcResult incident = mockMvc.perform(post(incidentPath)
                        .header("Authorization", bearer(delivery.token()))
                        .header("If-Match", delivery.deliveryEtag())
                        .header("Idempotency-Key", "bom-source-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ACCESS_BLOCKED\",\"reason\":\"Access blocked\","
                                + "\"description\":\"Gate closed\",\"place\":\"North entrance\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.severity").value("BLOCKING"))
                .andExpect(jsonPath("$.operationalExceptionId").isNotEmpty()).andReturn();
        UUID exceptionId = UUID.fromString(json(incident).get("operationalExceptionId").asText());

        String listPath = "/api/v1/operational-exceptions";
        MvcResult listed = mockMvc.perform(get(listPath).header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(listed).get("exceptions").toString()).contains(exceptionId.toString());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.operational_exception_case where id=?",
                Integer.class, exceptionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.operational_exception_case where id=?",
                Integer.class, exceptionId)).isZero();
        long version = tenantJdbc().queryForObject("select version from logistics.delivery where tenant_id=? "
                        + "and workspace_id=? and id=?", Long.class, UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), delivery.deliveryId());

        mockMvc.perform(get(listPath + "/" + exceptionId + "/assignees")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.membershipId=='" + actorId + "')]").isNotEmpty());

        String claimKey = "bom-claim-" + uuid();
        String claimBody = "{\"reason\":\"Coordinate current access restriction\"}";
        MvcResult claimed = mockMvc.perform(post(listPath + "/" + exceptionId + "/claims")
                        .header("Authorization", bearer(managerToken)).header("If-Match", quote(version))
                        .header("Idempotency-Key", claimKey).contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.exception.status").value("CLAIMED"))
                .andExpect(jsonPath("$.exception.coordinationOwnerMembershipId").value(actorId)).andReturn();
        mockMvc.perform(post(listPath + "/" + exceptionId + "/claims")
                        .header("Authorization", bearer(managerToken)).header("If-Match", quote(version))
                        .header("Idempotency-Key", claimKey).contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post(listPath + "/" + exceptionId + "/resolutions")
                        .header("Authorization", bearer(managerToken))
                        .header("If-Match", claimed.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "bom-block-resolution-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Gate reported to dispatch\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXCEPTION_OUTCOME_NOT_AUTHORIZED"));
        String deliveryStatus = tenantJdbc().queryForObject("select status from logistics.delivery where id=?", String.class,
                delivery.deliveryId());
        assertThat(deliveryStatus).isEqualTo("IN_TRANSIT");
    }

    private ActiveDelivery createActiveDelivery() throws Exception {
        DispatchResource dispatch = createReservedDispatch();
        UUID deliveryId = UUID.fromString(dispatch.id());
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID driverMembershipId = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID userId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, driverMembershipId);
        tenantJdbc().update("update logistics.dispatch_order set status='IN_ROUTE',version=version+1,updated_at=current_timestamp where id=?",
                deliveryId);
        tenantJdbc().update("update logistics.delivery set status='IN_TRANSIT',version=version+1,updated_at=current_timestamp where id=?",
                deliveryId);
        tenantJdbc().update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,responsible_membership_id,"
                        + "operator_id,vehicle_reference,route_name,assigned_at,actor_membership_id) values (?,?,?,?,?,?,?,?,current_timestamp,?)",
                UUID.randomUUID(), tenant, workspace, deliveryId, driverMembershipId, userId, "VAN-TEST", "ROUTE-TEST", driverMembershipId);
        String token = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        MvcResult currentWorkday = mockMvc.perform(get("/api/v1/driver/workdays/current")
                .header("Authorization", bearer(token))).andReturn();
        if (currentWorkday.getResponse().getStatus() == 204) {
            mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization", bearer(token))
                            .header("Idempotency-Key", "bom-workday-" + uuid())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                    .andExpect(status().isOk());
        }
        MvcResult detail = mockMvc.perform(get("/api/v1/driver/deliveries/" + deliveryId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        MvcResult start = mockMvc.perform(post("/api/v1/driver/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", bearer(token))
                        .header("If-Match", detail.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "bom-attempt-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        UUID attemptId = UUID.fromString(json(start).get("attempt").get("id").asText());
        return new ActiveDelivery(deliveryId, attemptId, driverMembershipId, token,
                start.getResponse().getHeader("ETag"));
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private static String quote(long version) { return "\"" + version + "\""; }

    private record ActiveDelivery(UUID deliveryId, UUID attemptId, UUID membershipId, String token,
                                  String deliveryEtag) { }
}
