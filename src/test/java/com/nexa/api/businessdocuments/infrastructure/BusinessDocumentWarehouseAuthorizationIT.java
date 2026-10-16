package com.nexa.api.businessdocuments.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL coverage for BC-09 Warehouse evidence grants and listing fences. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BusinessDocumentWarehouseAuthorizationIT extends NexaWorkflowIntegrationSupport {
    private static final byte[] PHOTO = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/g5sAAAAASUVORK5CYII=");

    @Test
    void warehouseEvidenceUsesExplicitCurrentGrantAcrossUploadListReadAndDownload() throws Exception {
        String warehouseActor = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        UUID warehouseA = createWarehouse(warehouseActor, "WH-DOC-A-");
        UUID warehouseB = createWarehouse(warehouseActor, "WH-DOC-B-");

        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        grant(owner, warehouseA, membershipId(WAREHOUSE_EMAIL));
        grant(owner, warehouseB, membershipId(OWNER_EMAIL));
        warehouseActor = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        owner = accessToken(OWNER_EMAIL, "PLATFORM");

        String evidenceA = json(upload(warehouseActor, warehouseA, "warehouse-evidence-a-" + uuid())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.lifecycleStatus").value("AVAILABLE"))
                        .andReturn())
                .get("id").asText();
        String evidenceB = json(upload(owner, warehouseB, "warehouse-evidence-b-" + uuid())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.lifecycleStatus").value("AVAILABLE"))
                        .andReturn())
                .get("id").asText();

        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceA)
                        .header("Authorization", "Bearer " + warehouseActor))
                .andExpect(status().isOk());
        MvcResult warehouseList = mockMvc.perform(get("/api/v1/business-document-evidence")
                        .param("subjectType", "WAREHOUSE").param("subjectId", warehouseA.toString())
                        .header("Authorization", "Bearer " + warehouseActor))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(warehouseList).path("items").get(0).path("id").asText()).isEqualTo(evidenceA);

        MvcResult genericList = mockMvc.perform(get("/api/v1/business-document-evidence")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + warehouseActor))
                .andExpect(status().isOk()).andReturn();
        List<String> visibleSubjectIds = new ArrayList<>();
        json(genericList).path("items").forEach(item -> visibleSubjectIds.add(item.path("subjectId").asText()));
        assertThat(visibleSubjectIds).contains(warehouseA.toString()).doesNotContain(warehouseB.toString());

        mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + warehouseActor)
                        .header("Idempotency-Key", "warehouse-no-grant-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"WAREHOUSE\",\"subjectId\":\"" + warehouseB
                                + "\",\"originalFilename\":\"receipt.png\",\"declaredContentType\":\"image/png\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/business-document-evidence")
                        .param("subjectType", "WAREHOUSE").param("subjectId", warehouseB.toString())
                        .header("Authorization", "Bearer " + warehouseActor))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceB)
                        .header("Authorization", "Bearer " + warehouseActor))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/v1/business-document-evidence/" + evidenceB + "/content")
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", PHOTO))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header("Authorization", "Bearer " + warehouseActor)
                        .header("Idempotency-Key", "warehouse-evidence-b-" + uuid()))
                .andExpect(status().isNotFound());

        // Company Owner has document permissions, but no implicit Warehouse access.
        mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "owner-no-grant-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"WAREHOUSE\",\"subjectId\":\"" + warehouseA
                                + "\",\"originalFilename\":\"receipt.png\",\"declaredContentType\":\"image/png\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceA)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceA + "/downloads")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/business-document-evidence/" + evidenceB + "/downloads")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Content-SHA256"));
    }

    private UUID createWarehouse(String token, String prefix) throws Exception {
        String suffix = uuid().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        MvcResult created = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + prefix + suffix
                                + "\",\"name\":\"Business document grant test\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(created).get("id").asText());
    }

    private void grant(String ownerToken, UUID warehouseId, String membershipId) throws Exception {
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, UUID warehouseId,
            String idempotencyKey) throws Exception {
        return mockMvc.perform(multipart("/api/v1/business-document-evidence")
                .file(new MockMultipartFile("file", "receipt.png", "image/png", PHOTO))
                .param("subjectType", "WAREHOUSE").param("subjectId", warehouseId.toString())
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey));
    }
}
