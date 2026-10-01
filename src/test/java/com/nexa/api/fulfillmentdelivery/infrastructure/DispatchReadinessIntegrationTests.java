package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DispatchReadinessIntegrationTests extends NexaWorkflowIntegrationSupport {
    @Autowired
    private PhysicalAllocationCommands physicalAllocations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void currentReadyProjectionIncludesPhysicalPickingAndCurrentVersionsThenTracksHandover() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult readyTransition = prepareForDispatch(fixture);

        var prepared = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        String assignmentBody = "{\"responsibleMembershipId\":\"" + membershipId(LOGISTICS_EMAIL)
                + "\",\"physicalAllocationId\":\"" + prepared.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + prepared.get("physicalAllocationVersion").asLong() + "}";
        MvcResult assigned = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/driver-assignments")
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "readiness-driver-assignment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn();

        var before = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        assertThat(before.get("subjectKind").asText()).isEqualTo("PREPARED_FULFILLMENT");
        assertThat(before.get("fulfillmentId").asText()).isEqualTo(fixture.fulfillmentId().toString());
        assertThat(before.get("fulfillmentStatus").asText()).isEqualTo("READY_FOR_DISPATCH");
        assertThat(before.get("fulfillmentVersion").asLong()).isEqualTo(etagVersion(assigned.getResponse().getHeader("ETag")));
        assertThat(before.get("physicalAllocationId").asText()).isEqualTo(fixture.allocationId().toString());
        assertThat(before.get("physicalAllocationStatus").asText()).isEqualTo("ALLOCATED");
        assertThat(before.get("physicalAllocationVersion").asLong()).isEqualTo(fixture.allocationVersion());
        assertThat(before.get("deliveryId").isNull()).isTrue();
        assertThat(before.get("allocationComplete").asBoolean()).isTrue();
        assertThat(before.get("pickingComplete").asBoolean()).isTrue();
        assertThat(before.get("pickingEvidenceComplete").asBoolean()).isTrue();
        assertThat(before.get("ready").asBoolean()).isTrue();
        assertThat(before.get("reasons")).isEmpty();
        assertThat(before.get("asOf").asText()).isNotBlank();
        assertThat(before.get("lines").get(0).get("evidencedPickedQuantity").decimalValue())
                .isEqualByComparingTo(fixture.quantity());

        String outgoingKey = "readiness-outgoing-check-" + uuid();
        MvcResult outgoing = recordOutgoingCheck(fixture, assigned.getResponse().getHeader("ETag"),
                outgoingKey, fixture.allocationVersion(), fixture.quantity(), fixture.lotId(), 201);
        var check = json(outgoing);
        assertThat(check.get("matches").asBoolean()).isTrue();
        assertThat(check.get("current").asBoolean()).isTrue();
        MvcResult outgoingReplay = recordOutgoingCheck(fixture,
                assigned.getResponse().getHeader("ETag"), outgoingKey,
                fixture.allocationVersion(), fixture.quantity(), fixture.lotId(), 200);
        assertThat(json(outgoingReplay).get("id").asText()).isEqualTo(check.get("id").asText());
        assertThat(json(outgoingReplay).get("replayed").asBoolean()).isTrue();

        var page = json(mockMvc.perform(get("/api/v1/dispatch-readiness")
                        .param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + fixture.coordinatorToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.get("asOf").asText()).isNotBlank();
        assertThat(textValues(page.get("items"), "fulfillmentId")).contains(fixture.fulfillmentId().toString());

        MvcResult handedOver = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "readiness-handover-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        var after = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        assertThat(after.get("fulfillmentStatus").asText()).isEqualTo("HANDED_OVER");
        assertThat(after.get("fulfillmentVersion").asLong()).isEqualTo(etagVersion(handedOver.getResponse().getHeader("ETag")));
        assertThat(after.get("fulfillmentVersion").asLong()).isGreaterThan(before.get("fulfillmentVersion").asLong());
        assertThat(after.get("physicalAllocationStatus").asText()).isEqualTo("CONSUMED");
        assertThat(after.get("physicalAllocationVersion").asLong()).isGreaterThan(before.get("physicalAllocationVersion").asLong());
        assertThat(after.get("deliveryId").isNull()).isFalse();
        assertThat(after.get("deliveryStatus").asText()).isEqualTo("DISPATCHED");
        assertThat(after.get("deliveryVersion").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(after.get("ready").asBoolean()).isFalse();
    }

    @Test
    void driverAssignmentUsesCurrentVersionsReplaysSafelyAndTransfersToRealDelivery() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult readyTransition = prepareForDispatch(fixture);
        var ready = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        long readyVersion = ready.get("fulfillmentVersion").asLong();
        long allocationVersion = ready.get("physicalAllocationVersion").asLong();
        String membershipId = membershipId(LOGISTICS_EMAIL);
        String assignmentPath = "/api/v1/fulfillments/" + fixture.fulfillmentId() + "/driver-assignments";
        String body = "{\"responsibleMembershipId\":\"" + membershipId + "\",\"physicalAllocationId\":\""
                + ready.get("physicalAllocationId").asText() + "\",\"physicalAllocationVersion\":" + allocationVersion + "}";
        String key = "driver-assignment-" + uuid();

        mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        String wrongAllocation = "{\"responsibleMembershipId\":\"" + membershipId + "\",\"physicalAllocationId\":\""
                + ready.get("physicalAllocationId").asText() + "\",\"physicalAllocationVersion\":" + (allocationVersion + 1) + "}";
        mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "driver-assignment-stale-allocation-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(wrongAllocation))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        MvcResult created = mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        var assigned = json(created);
        String assignmentId = assigned.get("id").asText();
        assertThat(assigned.get("fulfillmentId").asText()).isEqualTo(fixture.fulfillmentId().toString());
        assertThat(assigned.get("fulfillmentVersion").asLong()).isEqualTo(readyVersion + 1);
        assertThat(assigned.get("physicalAllocationId").asText()).isEqualTo(ready.get("physicalAllocationId").asText());
        assertThat(assigned.get("physicalAllocationVersion").asLong()).isEqualTo(allocationVersion);
        assertThat(assigned.get("responsibleMembershipId").asText()).isEqualTo(membershipId);
        assertThat(assigned.get("deliveryId").isNull()).isTrue();

        MvcResult replay = mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(assignmentId);

        mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongAllocation))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        MvcResult check = recordOutgoingCheck(fixture, created.getResponse().getHeader("ETag"),
                "assignment-outgoing-check-" + uuid(), allocationVersion,
                fixture.quantity(), fixture.lotId(), 201);
        String dispatchBody = "{\"physicalAllocationId\":\"" + ready.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + allocationVersion
                + ",\"driverAssignmentId\":\"" + assignmentId + "\",\"driverAssignmentVersion\":"
                + assigned.get("fulfillmentVersion").asLong() + ",\"outgoingGoodsCheckId\":\""
                + json(check).get("id").asText() + "\"}";
        String handoverKey = "assignment-handover-" + uuid();
        MvcResult handedOver = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", handoverKey)
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk()).andReturn();
        var deliveryId = json(handedOver).get("deliveryId").asText();
        assertThat(deliveryId).isNotBlank();

        MvcResult handoverReplay = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", handoverKey)
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(handoverReplay).get("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(jdbc.queryForObject("select count(*) from logistics.fulfillment_handoff_evidence where fulfillment_id=?",
                Integer.class, fixture.fulfillmentId())).isEqualTo(1);
        var handoffEvidence = json(mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId()
                        + "/handoff-evidence/current").header("Authorization", "Bearer " + fixture.coordinatorToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(handoffEvidence.get("fulfillmentId").asText()).isEqualTo(fixture.fulfillmentId().toString());
        assertThat(handoffEvidence.get("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(handoffEvidence.get("warehouseActorMembershipId").asText()).isEqualTo(membershipId(WAREHOUSE_EMAIL));
        assertThat(handoffEvidence.get("driverAssignmentId").asText()).isEqualTo(assignmentId);
        assertThat(handoffEvidence.get("driverMembershipId").asText()).isEqualTo(membershipId);
        assertThat(handoffEvidence.get("outgoingGoodsCheckId").asText()).isEqualTo(json(check).get("id").asText());
        assertThat(handoffEvidence.get("occurredAt").asText()).isNotBlank();
        assertThat(handoffEvidence.get("current").asBoolean()).isTrue();

        var transferred = json(mockMvc.perform(get(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(transferred.get("id").asText()).isEqualTo(assignmentId);
        assertThat(transferred.get("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_assignment "
                        + "where tenant_id=? and workspace_id=? and fulfillment_driver_assignment_id=? and delivery_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(assignmentId), UUID.fromString(deliveryId))).isEqualTo(1);
    }

    @Test
    void changedOutgoingCheckSnapshotRejectsDispatchBeforePhysicalConsumption() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult readyTransition = prepareForDispatch(fixture);
        var ready = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        String assignmentPath = "/api/v1/fulfillments/" + fixture.fulfillmentId() + "/driver-assignments";
        String assignmentBody = "{\"responsibleMembershipId\":\"" + membershipId(LOGISTICS_EMAIL)
                + "\",\"physicalAllocationId\":\"" + ready.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + ready.get("physicalAllocationVersion").asLong() + "}";
        MvcResult assignment = mockMvc.perform(post(assignmentPath)
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", readyTransition.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "dispatch-snapshot-assignment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult firstCheck = recordOutgoingCheck(fixture, assignment.getResponse().getHeader("ETag"),
                "dispatch-snapshot-check-old-" + uuid(), fixture.allocationVersion(),
                fixture.quantity(), fixture.lotId(), 201);
        MvcResult latestCheck = recordOutgoingCheck(fixture, assignment.getResponse().getHeader("ETag"),
                "dispatch-snapshot-check-new-" + uuid(), fixture.allocationVersion(),
                fixture.quantity(), fixture.lotId(), 201);
        var currentAllocation = json(physicalAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn());
        String staleBody = "{\"physicalAllocationId\":\"" + ready.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                + ",\"driverAssignmentId\":\"" + json(assignment).get("id").asText()
                + "\",\"driverAssignmentVersion\":" + json(assignment).get("fulfillmentVersion").asLong()
                + ",\"outgoingGoodsCheckId\":\"" + json(firstCheck).get("id").asText() + "\"}";

        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assignment.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "dispatch-snapshot-stale-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(staleBody))
                .andExpect(status().isPreconditionFailed());

        var after = json(physicalAllocation(fixture, fixture.warehouseToken()).andExpect(status().isOk()).andReturn());
        assertThat(after.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(after.get("version").asLong()).isEqualTo(currentAllocation.get("version").asLong());
        assertThat(json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn())
                .get("fulfillmentStatus").asText()).isEqualTo("READY_FOR_DISPATCH");
        assertThat(json(latestCheck).get("id").asText()).isNotEqualTo(json(firstCheck).get("id").asText());
    }

    @Test
    void outgoingDiscrepancyBlocksHandoverUntilExplicitResolutionAndCurrentMatch() throws Exception {
        Fixture fixture = createFulfillment();
        MvcResult ready = prepareForDispatch(fixture);
        var readiness = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        String assignmentBody = "{\"responsibleMembershipId\":\"" + membershipId(LOGISTICS_EMAIL)
                + "\",\"physicalAllocationId\":\"" + readiness.get("physicalAllocationId").asText()
                + "\",\"physicalAllocationVersion\":" + readiness.get("physicalAllocationVersion").asLong() + "}";
        MvcResult assigned = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/driver-assignments")
                        .header("Authorization", "Bearer " + fixture.coordinatorToken())
                        .header("If-Match", ready.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "discrepancy-driver-assignment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn();
        var before = json(physicalAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/outgoing-checks")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "outgoing-stale-version-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outgoingCheckBody(fixture, fixture.allocationVersion() + 1,
                                fixture.quantity(), fixture.lotId())))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        MvcResult recorded = recordOutgoingCheck(fixture, assigned.getResponse().getHeader("ETag"),
                "outgoing-mismatch-" + uuid(), fixture.allocationVersion(),
                fixture.quantity().add(java.math.BigDecimal.ONE), fixture.lotId(), 201);
        assertThat(json(recorded).get("matches").asBoolean()).isFalse();
        assertThat(json(recorded).get("openDiscrepancy").asBoolean()).isTrue();

        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "outgoing-blocked-handover-" + uuid()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_OUTGOING_DISCREPANCY_OPEN"));

        MvcResult reinspection = recordOutgoingCheck(fixture, assigned.getResponse().getHeader("ETag"),
                "outgoing-reinspection-" + uuid(), fixture.allocationVersion(),
                fixture.quantity(), fixture.lotId(), 201);
        var reinspectionJson = json(reinspection);
        assertThat(reinspectionJson.get("matches").asBoolean()).isTrue();
        assertThat(reinspectionJson.get("openDiscrepancy").asBoolean()).isTrue();
        assertThat(reinspectionJson.get("discrepancy").get("id").asText()).isEqualTo(json(recorded).get("id").asText());

        String resolutionKey = "outgoing-resolution-" + uuid();
        String resolutionBody = "{\"physicalAllocationId\":\"" + fixture.allocationId()
                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                + ",\"discrepancyCheckId\":\"" + json(recorded).get("id").asText()
                + "\",\"matchingCheckId\":\"" + reinspectionJson.get("id").asText()
                + "\",\"reason\":\"Recount confirmed the allocated lot and quantity.\"}";
        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId()
                        + "/outgoing-discrepancy-resolutions")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", resolutionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalAllocationId\":\"" + fixture.allocationId()
                                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                                + ",\"discrepancyCheckId\":\"" + json(recorded).get("id").asText()
                                + "\",\"matchingCheckId\":\"" + json(recorded).get("id").asText()
                                + "\",\"reason\":\"Incorrect match reference.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_OUTGOING_CHECK_REQUIRED"));

        MvcResult resolved = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId()
                        + "/outgoing-discrepancy-resolutions")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", resolutionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(resolutionBody))
                .andExpect(status().isCreated()).andReturn();
        var resolution = json(resolved);
        assertThat(resolution.get("discrepancyCheckId").asText()).isEqualTo(json(recorded).get("id").asText());
        assertThat(resolution.get("matchingCheckId").asText()).isEqualTo(reinspectionJson.get("id").asText());
        assertThat(resolution.get("reason").asText()).isEqualTo("Recount confirmed the allocated lot and quantity.");
        assertThat(resolution.get("current").asBoolean()).isTrue();

        MvcResult resolutionReplay = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId()
                        + "/outgoing-discrepancy-resolutions")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", resolutionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(resolutionBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(resolutionReplay).get("id").asText()).isEqualTo(resolution.get("id").asText());
        assertThat(json(resolutionReplay).get("replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from logistics.fulfillment_outgoing_goods_check "
                        + "where tenant_id=? and workspace_id=? and id=? and matches=false",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(json(recorded).get("id").asText()))).isEqualTo(1);

        String handoverBody = "{\"physicalAllocationId\":\"" + fixture.allocationId()
                + "\",\"physicalAllocationVersion\":" + fixture.allocationVersion()
                + ",\"driverAssignmentId\":\"" + json(assigned).get("id").asText()
                + "\",\"driverAssignmentVersion\":" + json(assigned).get("fulfillmentVersion").asLong()
                + ",\"outgoingGoodsCheckId\":\"" + reinspectionJson.get("id").asText() + "\"}";
        var beforeHandoverReadiness = json(readiness(fixture, fixture.coordinatorToken())
                .andExpect(status().isOk()).andReturn());
        assertThat(beforeHandoverReadiness.get("fulfillmentVersion").asLong())
                .isEqualTo(json(assigned).get("fulfillmentVersion").asLong());
        var currentAssignment = json(mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId()
                        + "/driver-assignments")
                        .header("Authorization", "Bearer " + fixture.coordinatorToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(currentAssignment.get("id").asText()).isEqualTo(json(assigned).get("id").asText());
        assertThat(currentAssignment.get("fulfillmentVersion").asLong())
                .isEqualTo(json(assigned).get("fulfillmentVersion").asLong());
        mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/dispatches")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", assigned.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "outgoing-resolved-handover-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(handoverBody))
                .andExpect(status().isOk());

        var after = json(physicalAllocation(fixture, fixture.warehouseToken())
                .andExpect(status().isOk()).andReturn());
        assertThat(after.get("status").asText()).isEqualTo("CONSUMED");
        assertThat(after.get("version").asLong()).isGreaterThan(before.get("version").asLong());
        assertThat(after.get("lines").get(0).get("remainingQuantity").decimalValue())
                .isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    @Test
    void incompletePhysicalAllocationIsVisibleAsNotReady() throws Exception {
        Fixture fixture = createFulfillment();
        String idempotencyKey = "readiness-release-" + uuid();
        String requestHash = sha256("release|" + fixture.fulfillmentId() + "|" + idempotencyKey);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> physicalAllocations.release(
                new PhysicalAllocationCommands.ReleaseRequest(
                        UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.fulfillmentId(),
                        UUID.fromString(membershipId(WAREHOUSE_EMAIL)), idempotencyKey, requestHash,
                        fixture.allocationVersion(), "Readiness integration fixture release", Instant.now())));

        var readiness = json(readiness(fixture, fixture.coordinatorToken()).andExpect(status().isOk()).andReturn());
        assertThat(readiness.get("physicalAllocationStatus").asText()).isEqualTo("RELEASED");
        assertThat(readiness.get("allocationComplete").asBoolean()).isFalse();
        assertThat(readiness.get("pickingEvidenceComplete").asBoolean()).isFalse();
        assertThat(readiness.get("ready").asBoolean()).isFalse();
        assertThat(readiness.get("reasons").toString())
                .contains("PHYSICAL_ALLOCATION_NOT_CURRENT", "PICKING_EVIDENCE_INCOMPLETE");
    }

    @Test
    void partialPickingAndMissingPickingEvidenceFailClosed() throws Exception {
        Fixture partial = createFulfillment();
        var absentEvidence = json(readiness(partial, partial.coordinatorToken()).andExpect(status().isOk()).andReturn());
        assertThat(absentEvidence.get("pickingComplete").asBoolean()).isFalse();
        assertThat(absentEvidence.get("pickingEvidenceComplete").asBoolean()).isFalse();
        assertThat(absentEvidence.get("ready").asBoolean()).isFalse();

        MvcResult partialStart = startPicking(partial);
        mockMvc.perform(post("/api/v1/fulfillments/" + partial.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + partial.warehouseToken())
                        .header("If-Match", partialStart.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "readiness-partial-pick-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pickingBody(partial, partial.quantity().divide(java.math.BigDecimal.valueOf(2)))))
                .andExpect(status().isOk());
        var incompletePick = json(readiness(partial, partial.coordinatorToken()).andExpect(status().isOk()).andReturn());
        assertThat(incompletePick.get("pickingComplete").asBoolean()).isFalse();
        assertThat(incompletePick.get("pickingEvidenceComplete").asBoolean()).isFalse();
        assertThat(incompletePick.get("ready").asBoolean()).isFalse();
        assertThat(incompletePick.get("reasons").toString())
                .contains("PICKING_INCOMPLETE", "PICKING_EVIDENCE_INCOMPLETE");
    }

    @Test
    void activeGrantForWarehouseBDoesNotExposeWarehouseAAndListCountIsFilteredFirst() throws Exception {
        Fixture fixture = createFulfillment();
        String logistics = fixture.coordinatorToken();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        var before = json(readinessList(logistics).andExpect(status().isOk()).andReturn());
        assertThat(textValues(before.get("items"), "fulfillmentId")).contains(fixture.fulfillmentId().toString());

        String warehouseB = createWarehouse(fixture.warehouseToken());
        grant(owner, warehouseB, membershipId(LOGISTICS_EMAIL)).andExpect(status().isOk());
        var grants = json(mockMvc.perform(get("/api/v1/warehouses/" + fixture.warehouseId() + "/access-grants")
                .header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn());
        List<tools.jackson.databind.JsonNode> grantItems = new ArrayList<>();
        grants.forEach(grantItems::add);
        var grant = grantItems.stream().filter(value -> membershipId(LOGISTICS_EMAIL)
                .equals(value.path("membershipId").asText())).findFirst().orElseThrow();
        mockMvc.perform(delete("/api/v1/warehouses/" + fixture.warehouseId() + "/access-grants/" + membershipId(LOGISTICS_EMAIL))
                        .header("Authorization", "Bearer " + owner)
                        .header("If-Match", "\"" + grant.path("version").asLong() + "\""))
                .andExpect(status().isOk());

        String currentLogistics = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        readiness(fixture, currentLogistics).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_NOT_FOUND"));
        var after = json(readinessList(currentLogistics).andExpect(status().isOk()).andReturn());
        assertThat(textValues(after.get("items"), "fulfillmentId")).doesNotContain(fixture.fulfillmentId().toString());
        assertThat(after.get("totalItems").asLong()).isLessThan(before.get("totalItems").asLong());
        assertThat(after.get("totalItems").asLong()).isEqualTo(after.get("items").size());
    }

    @Test
    void anotherTenantCannotReadPreparedFulfillment() throws Exception {
        Fixture fixture = createFulfillment();
        String foreignWorkspace = createWorkspaceForLogistics(uuid());
        String foreignActor = accessTokenForWorkspace(LOGISTICS_EMAIL, foreignWorkspace);
        readiness(fixture, foreignActor).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_NOT_FOUND"));
    }

    private Fixture createFulfillment() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String orderKey = "readiness-order-" + uuid();
        MvcResult order = mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", orderKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + buyerClientAccountId()
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                                + "\"deliveryProfileSnapshot\":\"Dispatch readiness integration\",\"paymentOption\":\"IMMEDIATE\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}") )
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(order).get("id").asText();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        var backingWarehouses = jdbc.query("select distinct p.warehouse_id from warehouse.inventory_backing b "
                        + "join sales.commercial_commitment c on c.id=b.commercial_commitment_id "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=b.tenant_id and l.workspace_id=b.workspace_id and l.backing_id=b.id "
                        + "join warehouse.inventory_backing_position p on p.tenant_id=l.tenant_id and p.workspace_id=l.workspace_id and p.backing_line_id=l.id "
                        + "where b.tenant_id=? and b.workspace_id=? and c.sales_order_id=? and b.status='BACKED'",
                (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), UUID.fromString(orderId));
        for (UUID backingWarehouse : backingWarehouses) {
            ensureGrant(owner, backingWarehouse.toString(), membershipId(WAREHOUSE_EMAIL));
        }
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/fulfillments")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("If-Match", order.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "readiness-fulfillment-" + uuid()))
                .andExpect(status().isCreated()).andReturn();
        var fulfillment = json(created);
        String allocationId = fulfillment.get("physicalAllocationId").asText();
        MvcResult allocationResult = mockMvc.perform(get("/api/v1/fulfillments/" + fulfillment.get("id").asText()
                        + "/physical-allocation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk()).andReturn();
        var allocation = json(allocationResult);
        var physicalLine = allocation.get("lines").get(0);
        var fulfillmentLine = fulfillment.get("lines").get(0);
        String warehouseId = physicalLine.get("warehouseId").asText();
        ensureGrant(owner, warehouseId, membershipId(LOGISTICS_EMAIL));
        String coordinator = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        return new Fixture(UUID.fromString(fulfillment.get("id").asText()),
                UUID.fromString(fulfillmentLine.get("id").asText()),
                UUID.fromString(physicalLine.get("skuId").asText()),
                UUID.fromString(allocationId), UUID.fromString(physicalLine.get("physicalAllocationLineId").asText()),
                UUID.fromString(physicalLine.get("lotId").asText()), UUID.fromString(warehouseId),
                physicalLine.get("quantity").decimalValue(), physicalLine.get("unit").asText(),
                allocation.get("version").asLong(), created.getResponse().getHeader("ETag"), warehouse, coordinator);
    }

    private MvcResult prepareForDispatch(Fixture fixture) throws Exception {
        MvcResult started = startPicking(fixture);
        MvcResult picked = mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-confirmations")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", started.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "readiness-pick-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(pickingBody(fixture, fixture.quantity())))
                .andExpect(status().isOk()).andReturn();
        MvcResult packed = step(fixture, "packing", picked.getResponse().getHeader("ETag"));
        MvcResult staged = step(fixture, "staging", packed.getResponse().getHeader("ETag"));
        return step(fixture, "ready-for-dispatch", staged.getResponse().getHeader("ETag"));
    }

    private MvcResult startPicking(Fixture fixture) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/picking-starts")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", fixture.fulfillmentEtag())
                        .header("Idempotency-Key", "readiness-picking-start-" + uuid()))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult step(Fixture fixture, String action, String etag) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/" + action)
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "readiness-" + action + "-" + uuid()))
                .andExpect(status().isOk()).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions readiness(Fixture fixture, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/dispatch-readiness/" + fixture.fulfillmentId())
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions physicalAllocation(Fixture fixture, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/physical-allocation")
                .header("Authorization", "Bearer " + token));
    }

    private MvcResult recordOutgoingCheck(Fixture fixture, String fulfillmentEtag, String key,
                                          long allocationVersion, java.math.BigDecimal observedQuantity,
                                          UUID observedLotId, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/fulfillments/" + fixture.fulfillmentId() + "/outgoing-checks")
                        .header("Authorization", "Bearer " + fixture.warehouseToken())
                        .header("If-Match", fulfillmentEtag)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outgoingCheckBody(fixture, allocationVersion, observedQuantity, observedLotId)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private static String outgoingCheckBody(Fixture fixture, long allocationVersion,
                                            java.math.BigDecimal observedQuantity, UUID observedLotId) {
        return "{\"physicalAllocationId\":\"" + fixture.allocationId() + "\",\"physicalAllocationVersion\":"
                + allocationVersion + ",\"observations\":[{\"physicalAllocationLineId\":\""
                + fixture.allocationLineId() + "\",\"observedLotId\":\"" + observedLotId
                + "\",\"observedQuantity\":" + observedQuantity.toPlainString() + "}]}";
    }

    private org.springframework.test.web.servlet.ResultActions readinessList(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/dispatch-readiness").param("page", "0").param("size", "100")
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions grant(String owner, String warehouseId,
                                                                        String membershipId) throws Exception {
        return mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipId\":\"" + membershipId + "\"}"));
    }

    private void ensureGrant(String owner, String warehouseId, String targetMembershipId) throws Exception {
        var grants = json(mockMvc.perform(get("/api/v1/warehouses/" + warehouseId + "/access-grants")
                .header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn());
        tools.jackson.databind.JsonNode prior = null;
        for (tools.jackson.databind.JsonNode grant : grants) {
            if (targetMembershipId.equals(grant.path("membershipId").asText())) {
                prior = grant;
                if ("ACTIVE".equals(grant.path("status").asText())) return;
            }
        }
        var request = post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipId\":\"" + targetMembershipId + "\"}");
        if (prior != null) request.header("If-Match", "\"" + prior.path("version").asLong() + "\"");
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private String createWarehouse(String token) throws Exception {
        String code = "WH-RD-" + uuid().replace("-", "").substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        MvcResult created = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + code
                                + "\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(created).get("id").asText();
    }

    private String createWorkspaceForLogistics(String suffix) {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String tenantSlug = "readiness-tenant-" + suffix.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String workspaceSlug = "readiness-workspace-" + suffix.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        UUID sourceMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenant, "Dispatch readiness isolation tenant", tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspace, tenant, "Dispatch readiness isolation workspace", workspaceSlug);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "select ?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                        + "from tenant_management.workspace_membership where id=?",
                membership, workspace, sourceMembership);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition "
                        + "where membership_id=?",
                membership, tenant, workspace, sourceMembership);
        return workspaceSlug;
    }

    private String accessTokenForWorkspace(String email, String workspaceSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
                                + "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("accessToken").asText();
    }

    private static List<String> textValues(tools.jackson.databind.JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> {
            if (item.has(field) && item.get(field).isTextual()) values.add(item.get(field).asText());
        });
        return values;
    }

    private static String pickingBody(Fixture fixture, java.math.BigDecimal quantity) {
        return "{\"allocationVersion\":" + fixture.allocationVersion() + ",\"lines\":[{\"fulfillmentLineId\":\""
                + fixture.fulfillmentLineId() + "\",\"skuId\":\"" + fixture.skuId() + "\",\"quantity\":"
                + quantity.toPlainString() + ",\"unit\":\"" + fixture.unit() + "\",\"physicalAllocationLineId\":\""
                + fixture.allocationLineId() + "\",\"lotId\":\"" + fixture.lotId() + "\",\"warehouseId\":\""
                + fixture.warehouseId() + "\"}]}";
    }

    private static long etagVersion(String etag) {
        return Long.parseLong(etag.replace("\"", ""));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Fixture(UUID fulfillmentId, UUID fulfillmentLineId, UUID skuId,
                           UUID allocationId, UUID allocationLineId, UUID lotId, UUID warehouseId,
                           java.math.BigDecimal quantity, String unit, long allocationVersion,
                           String fulfillmentEtag, String warehouseToken, String coordinatorToken) { }
}
