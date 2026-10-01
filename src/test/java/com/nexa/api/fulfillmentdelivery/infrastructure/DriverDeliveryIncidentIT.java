package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL and HTTP coverage for append-only current-driver incident facts. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "spring.datasource.hikari.minimum-idle=1")
class DriverDeliveryIncidentIT extends NexaWorkflowIntegrationSupport {

    @Test
    void recordsActiveAttemptIncidentAndExactSubjectEvidenceWithoutRewritingOutcome() throws Exception {
        ensureCommercialInventory();
        ActiveDelivery fixture = createActiveDelivery();
        String incidentPath = "/api/v1/driver/deliveries/" + fixture.deliveryId() + "/attempts/"
                + fixture.attemptId() + "/incidents";
        String incidentKey = "driver-incident-" + UUID.randomUUID();
        String body = "{\"type\":\"ACCESS_BLOCKED\",\"reason\":\"Access blocked\",\"description\":\"Gate is closed\",\"place\":\"North entrance\"}";

        MvcResult recorded = mockMvc.perform(post(incidentPath)
                        .header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", fixture.deliveryEtag()).header("Idempotency-Key", incidentKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("Access blocked"))
                .andExpect(jsonPath("$.type").value("ACCESS_BLOCKED"))
                .andExpect(jsonPath("$.severity").value("BLOCKING"))
                .andExpect(jsonPath("$.operationalExceptionId").isNotEmpty())
                .andExpect(jsonPath("$.description").value("Gate is closed"))
                .andExpect(jsonPath("$.place").value("North entrance"))
                .andExpect(jsonPath("$.attemptId").value(fixture.attemptId().toString()))
                .andExpect(jsonPath("$.recordedByMembershipId").value(fixture.membershipId().toString()))
                .andExpect(jsonPath("$.evidenceObjectIds").isEmpty()).andReturn();
        UUID incidentId = UUID.fromString(json(recorded).get("id").asText());
        UUID exceptionId = UUID.fromString(json(recorded).get("operationalExceptionId").asText());
        String incidentEtag = recorded.getResponse().getHeader("ETag");

        long replayVersion = Long.parseLong(incidentEtag.substring(1, incidentEtag.length() - 1));
        String legacyReason = "Old untyped reason";
        String legacyDescription = "Saved before classification";
        String legacyPlace = "Side entrance";
        String legacyKey = "legacy-driver-incident-" + UUID.randomUUID();
        String legacyHash = legacyIncidentHash(UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                fixture.membershipId(), fixture.deliveryId(), fixture.attemptId(), replayVersion,
                legacyReason, legacyDescription, legacyPlace);
        UUID legacyIncidentId = UUID.randomUUID();
        Timestamp legacyReportedAt = Timestamp.from(Instant.now());
        jdbc.update("insert into logistics.driver_delivery_incident(id,tenant_id,workspace_id,delivery_id,"
                        + "delivery_attempt_id,reason,description,place,reported_by_membership_id,reported_at,"
                        + "delivery_version,request_hash) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                legacyIncidentId, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.deliveryId(),
                fixture.attemptId(), legacyReason, legacyDescription, legacyPlace, fixture.membershipId(),
                legacyReportedAt, replayVersion, legacyHash);
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,"
                        + "operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()), fixture.membershipId(),
                "DRIVER_INCIDENT", legacyKey, legacyHash, legacyIncidentId, legacyReportedAt);
        MvcResult legacyReplay = mockMvc.perform(post(incidentPath)
                        .header("Authorization", "Bearer " + fixture.token()).header("If-Match", incidentEtag)
                        .header("Idempotency-Key", legacyKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + legacyReason + "\",\"description\":\""
                                + legacyDescription + "\",\"place\":\"" + legacyPlace + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(legacyIncidentId.toString()))
                .andExpect(jsonPath("$.replayed").value(true)).andReturn();
        assertThat(json(legacyReplay).get("type").isNull()).isTrue();
        assertThat(json(legacyReplay).get("severity").isNull()).isTrue();
        assertThat(json(legacyReplay).get("operationalExceptionId").isNull()).isTrue();

        mockMvc.perform(post(incidentPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", "untyped-new-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Reason\",\"description\":\"Description\",\"place\":\"Entrance\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRIVER_INCIDENT_TYPE_REQUIRED"));

        mockMvc.perform(post(incidentPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", fixture.deliveryEtag()).header("Idempotency-Key", incidentKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post(incidentPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", fixture.deliveryEtag()).header("Idempotency-Key", incidentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("North entrance", "South entrance")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        mockMvc.perform(post(incidentPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", fixture.deliveryEtag()).header("Idempotency-Key", "incomplete-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Access blocked\",\"description\":\"\",\"place\":\"North entrance\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + fixture.token())
                        .header("Idempotency-Key", "incident-upload-request-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"DELIVERY_INCIDENT\",\"subjectId\":\"" + incidentId
                                + "\",\"originalFilename\":\"incident.jpg\",\"declaredContentType\":\"image/jpeg\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subjectType").value("DELIVERY_INCIDENT"))
                .andExpect(jsonPath("$.subjectId").value(incidentId.toString()));

        UUID invalidEvidence = seedIncidentEvidence(UUID.randomUUID(), fixture.clientAccountId(), fixture.membershipId());
        UUID validEvidence = seedIncidentEvidence(incidentId, fixture.clientAccountId(), fixture.membershipId());
        String evidencePath = incidentPath + "/" + incidentId + "/evidence";
        String evidenceKey = "driver-incident-evidence-" + UUID.randomUUID();
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceObjectIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));
        // A same-tenant available object bound to a different incident is not accepted.
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceObjectIds\":[\"" + invalidEvidence + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));

        String evidenceBody = "{\"evidenceObjectIds\":[\"" + validEvidence + "\"]}";
        MvcResult attached = mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(evidenceBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidenceObjectIds[0]").value(validEvidence.toString()))
                .andReturn();
        String attachedAt = json(attached).get("recordedAt").asText();
        mockMvc.perform(post(evidencePath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON).content(evidenceBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordedAt").value(attachedAt))
                .andExpect(jsonPath("$.replayed").value(true));

        String exceptionCollection = "/api/v1/driver/deliveries/" + fixture.deliveryId()
                + "/operational-exceptions";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(exceptionCollection)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryVersion").value(replayVersion))
                .andExpect(jsonPath("$.exceptions[0].id").value(exceptionId.toString()))
                .andExpect(jsonPath("$.exceptions[0].sourceKind").value("DRIVER_INCIDENT"))
                .andExpect(jsonPath("$.exceptions[0].sourceIncidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.exceptions[0].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.exceptions[0].status").value("OPEN"))
                .andExpect(jsonPath("$.exceptions[0].evidenceObjectIds[0]").value(validEvidence.toString()));

        String claimPath = exceptionCollection + "/" + exceptionId + "/claims";
        String claimKey = "operational-exception-claim-" + UUID.randomUUID();
        MvcResult claim = mockMvc.perform(post(claimPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", claimKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.exception.status").value("CLAIMED"))
                .andExpect(jsonPath("$.exception.responsibleMembershipId").value(fixture.membershipId().toString()))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        String claimEtag = claim.getResponse().getHeader("ETag");
        mockMvc.perform(post(claimPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", incidentEtag).header("Idempotency-Key", claimKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        String reviewPath = exceptionCollection + "/" + exceptionId + "/reviews";
        String reviewKey = "operational-exception-review-" + UUID.randomUUID();
        MvcResult review = mockMvc.perform(post(reviewPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", claimEtag).header("Idempotency-Key", reviewKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.exception.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.exception.underReviewByMembershipId").value(fixture.membershipId().toString()))
                .andReturn();
        String reviewEtag = review.getResponse().getHeader("ETag");
        mockMvc.perform(post(reviewPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", claimEtag).header("Idempotency-Key", reviewKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post(reviewPath).header("Authorization", "Bearer " + fixture.token())
                        .header("If-Match", claimEtag).header("Idempotency-Key", "stale-review-" + UUID.randomUUID()))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(exceptionCollection)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk()).andExpect(header -> assertThat(header.getResponse().getHeader("ETag"))
                        .isEqualTo(reviewEtag))
                .andExpect(jsonPath("$.exceptions[0].status").value("UNDER_REVIEW"));

        assertThat(jdbc.queryForObject("select status from logistics.delivery where id=?", String.class,
                fixture.deliveryId())).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_active_attempt where delivery_id=? and id=?",
                Integer.class, fixture.deliveryId(), fixture.attemptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_delivery_incident where id=?",
                Integer.class, incidentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.operational_exception_case where id=? "
                + "and source_kind='DRIVER_INCIDENT' and source_driver_incident_id=? and severity='BLOCKING'",
                Integer.class, exceptionId, incidentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.operational_exception_transition "
                + "where exception_id=? and transition_number=1 and to_status='OPEN'",
                Integer.class, exceptionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_delivery_incident_evidence where incident_id=?",
                Integer.class, incidentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?",
                Integer.class, fixture.deliveryId())).isZero();
    }

    private ActiveDelivery createActiveDelivery() throws Exception {
        DispatchResource dispatch = createReservedDispatch();
        UUID deliveryId = UUID.fromString(dispatch.id());
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID membershipId = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID userId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, membershipId);
        jdbc.update("update logistics.dispatch_order set status='IN_ROUTE',version=version+1,updated_at=current_timestamp where id=?",
                deliveryId);
        jdbc.update("update logistics.delivery set status='IN_TRANSIT',version=version+1,updated_at=current_timestamp where id=?",
                deliveryId);
        jdbc.update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,responsible_membership_id,"
                        + "operator_id,vehicle_reference,route_name,assigned_at,actor_membership_id) values (?,?,?,?,?,?,?,?,current_timestamp,?)",
                UUID.randomUUID(), tenant, workspace, deliveryId, membershipId, userId, "VAN-TEST", "ROUTE-TEST", membershipId);
        String token = accessToken(LOGISTICS_EMAIL, "PLATFORM");
        var currentWorkday = mockMvc.perform(get("/api/v1/driver/workdays/current")
                        .header("Authorization", "Bearer " + token)).andReturn();
        if (currentWorkday.getResponse().getStatus() == 204) {
        mockMvc.perform(post("/api/v1/driver/workdays")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "incident-workday-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":true}"))
                .andExpect(status().isOk());
        }
        MvcResult detail = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/driver/deliveries/" + deliveryId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        MvcResult start = mockMvc.perform(post("/api/v1/driver/deliveries/" + deliveryId + "/attempts")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", detail.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "incident-attempt-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        UUID attemptId = UUID.fromString(json(start).get("attempt").get("id").asText());
        UUID clientAccount = jdbc.queryForObject("select client_account_id from logistics.dispatch_order where id=?",
                UUID.class, deliveryId);
        return new ActiveDelivery(deliveryId, attemptId, membershipId, clientAccount, token,
                start.getResponse().getHeader("ETag"));
    }

    private UUID seedIncidentEvidence(UUID incidentId, UUID clientAccountId, UUID actorMembershipId) {
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "evidence/test/driver-incident-" + UUID.randomUUID() + ".jpg";
        String checksum = "b".repeat(64);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into business_documents.object_storage_object "
                        + "(object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?)", objectKey, UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), "nexa-private", checksum, "image/jpeg", 8L, true, now);
        jdbc.update("insert into business_documents.evidence_object "
                        + "(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,"
                        + "declared_content_type,detected_content_type,original_filename,checksum_sha256,byte_size,created_at,scanned_at,"
                        + "requested_by_membership_id,idempotency_key,scan_attempt_count,next_scan_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,'AVAILABLE','image/jpeg','image/jpeg','incident-evidence.jpg',?,?,?, ?,?,?,0,?,?)",
                evidenceId, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), clientAccountId,
                "DELIVERY_INCIDENT", incidentId, objectKey, checksum, 8L, now, now, actorMembershipId,
                "seed-driver-incident-" + UUID.randomUUID(), now, now);
        return evidenceId;
    }

    private static String legacyIncidentHash(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                             UUID deliveryId, UUID attemptId, long expectedVersion,
                                             String reason, String description, String place) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "driver-delivery-incident-v1");
            for (Object part : new Object[]{tenantId, workspaceId, actorMembershipId, deliveryId, attemptId,
                    expectedVersion, reason, description, place}) add(digest, part.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record ActiveDelivery(UUID deliveryId, UUID attemptId, UUID membershipId, UUID clientAccountId,
                                  String token, String deliveryEtag) { }
}
