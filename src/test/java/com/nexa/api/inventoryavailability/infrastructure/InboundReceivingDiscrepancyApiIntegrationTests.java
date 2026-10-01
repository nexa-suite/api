package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL checks for immutable, evidence-gated receiving observations. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class InboundReceivingDiscrepancyApiIntegrationTests extends NexaWorkflowIntegrationSupport {
    @Test
    void receivingEvidenceReadUploadAndSubmissionRequireCurrentWarehouseGrant() throws Exception {
        String suffix = uuid().substring(0, 8).toUpperCase();
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult warehouseCreated = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-INB-AUTH-" + suffix + "\",\"name\":\"Inbound evidence grant test\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID warehouseA = UUID.fromString(json(warehouseCreated).get("id").asText());
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseA + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        MvcResult warehouseBCreated = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-INB-B-" + suffix + "\",\"name\":\"Other inbound warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID warehouseB = UUID.fromString(json(warehouseBCreated).get("id").asText());
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseB + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        UUID skuId = UUID.fromString(jdbc.queryForObject("select id::text from catalog_management.sellable_sku "
                + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002' and status='ACTIVE' limit 1",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId())));
        String createBody = "{\"warehouseId\":\"" + warehouseB + "\",\"expectedSkuId\":\"" + skuId
                + "\",\"observedSkuId\":\"" + skuId + "\",\"expectedQuantity\":6,\"observedQuantity\":5,"
                + "\"unit\":\"UNIT\",\"reason\":\"One unit missing at receipt\"}";
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", "inbound-auth-create-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        UUID caseId = UUID.fromString(json(created).get("id").asText());
        String evidenceKey = "inbound-auth-evidence-" + uuid();
        MvcResult requested = mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", evidenceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"INBOUND_RECEIVING_DISCREPANCY\",\"subjectId\":\"" + caseId
                                + "\",\"originalFilename\":\"receipt.jpg\",\"declaredContentType\":\"image/jpeg\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID evidenceId = UUID.fromString(json(requested).get("id").asText());
        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/warehouses/" + warehouseB + "/access-grants/" + membershipId(WAREHOUSE_EMAIL))
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk());
        warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceId)
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/business-document-evidence")
                        .param("subjectType", "INBOUND_RECEIVING_DISCREPANCY")
                        .param("subjectId", caseId.toString())
                        .header("Authorization", "Bearer " + warehouseToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(multipart("/api/v1/business-document-evidence/" + evidenceId + "/content")
                        .file(new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", evidenceKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        UUID availableEvidence = insertEvidence(caseId, "AVAILABLE");
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "inbound-auth-submit-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceObjectId\":\"" + availableEvidence + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select status from warehouse.inbound_receiving_discrepancy_case where id=?",
                String.class, caseId)).isEqualTo("PENDING_EVIDENCE");
    }

    @Test
    void recordingAndSubmittingDiscrepancyNeverChangesStockAndRequiresExactAvailableEvidence() throws Exception {
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = uuid().substring(0, 8).toUpperCase();
        MvcResult warehouseCreated = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-INB-" + suffix + "\",\"name\":\"Receiving discrepancy test\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID warehouseId = UUID.fromString(json(warehouseCreated).get("id").asText());
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        UUID skuId = UUID.fromString(jdbc.queryForObject("select id::text from catalog_management.sellable_sku "
                + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id='CAT-0002' and status='ACTIVE' limit 1",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId())));
        MvcResult ungrantedWarehouse = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-NOG-" + suffix + "\",\"name\":\"Ungrant test\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();

        int stockMovementsBefore = jdbc.queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        String createKey = "inbound-discrepancy-" + uuid();
        String body = "{\"warehouseId\":\"" + warehouseId + "\",\"expectedSkuId\":\"" + skuId
                + "\",\"observedSkuId\":\"" + skuId + "\",\"expectedBatchReference\":\"EXPECTED-A\","
                + "\"observedBatchReference\":\"OBSERVED-B\",\"expectedQuantity\":10,\"observedQuantity\":8,"
                + "\"unit\":\"UNIT\",\"reason\":\"Physical count differs from expected receipt\","
                + "\"observationNotes\":\"Two units absent at arrival\"}";
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", "ungranted-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(warehouseId.toString(), json(ungrantedWarehouse).get("id").asText())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_EVIDENCE"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        String caseId = json(created).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(caseId));
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Physical count differs from expected receipt", "changed payload")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));

        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases")
                        .header("Authorization", "Bearer " + buyerToken)
                        .header("Idempotency-Key", "unauthorized-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        UUID mismatchedEvidence = insertEvidence(UUID.randomUUID(), "AVAILABLE");
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "submit-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceObjectId\":\"" + mismatchedEvidence + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));

        UUID quarantinedEvidence = insertEvidence(UUID.fromString(caseId), "QUARANTINED");
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "submit-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceObjectId\":\"" + quarantinedEvidence + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_EVIDENCE_NOT_AVAILABLE"));

        UUID availableEvidence = insertEvidence(UUID.fromString(caseId), "AVAILABLE");
        String submitKey = "submit-" + uuid();
        String submitBody = "{\"evidenceObjectId\":\"" + availableEvidence + "\"}";
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", submitKey)
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        MvcResult submitted = mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", submitKey)
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.version").value(1)).andReturn();
        assertThat(json(submitted).get("evidenceObjectId").asText()).isEqualTo(availableEvidence.toString());
        mockMvc.perform(post("/api/v1/inventory/inbound-discrepancy-cases/" + caseId + "/submissions")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", submitKey)
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"));
        assertThat(jdbc.queryForObject("select count(*) from warehouse.stock_movement where tenant_id=? and workspace_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()))).isEqualTo(stockMovementsBefore);
    }

    private UUID insertEvidence(UUID caseId, String status) {
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("insert into business_documents.evidence_object (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,"
                        + "object_key,lifecycle_status,declared_content_type,original_filename,created_at,requested_by_membership_id,idempotency_key) "
                        + "values (?,?,?,null,'INBOUND_RECEIVING_DISCREPANCY',?,null,?,'image/jpeg','receipt.jpg',?,?,?)",
                evidenceId, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), caseId, status,
                Timestamp.from(Instant.now()), UUID.fromString(membershipId(WAREHOUSE_EMAIL)), "evidence-" + evidenceId);
        return evidenceId;
    }
}
