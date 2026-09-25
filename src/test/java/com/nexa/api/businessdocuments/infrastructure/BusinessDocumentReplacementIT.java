package com.nexa.api.businessdocuments.infrastructure;

import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.businessdocuments.infrastructure.persistence.BusinessDocumentService;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BusinessDocumentReplacementIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private ObjectStoragePort storage;

    @Autowired
    private BusinessDocumentService documents;

    @Test
    void replacementIsLinkedAndIssuedOriginalRemainsDownloadable() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        UUID originalId = seedIssuedOrderSummary(order.id());
        String idempotencyKey = "replace-document-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/v1/business-documents/" + originalId + "/replacements")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted()).andReturn();
        String replacementId = json(first).get("documentId").asText();

        MvcResult retry = mockMvc.perform(post("/api/v1/business-documents/" + originalId + "/replacements")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(json(retry).get("documentId").asText()).isEqualTo(replacementId);

        documents.processPendingGenerationRequests();

        MvcResult replacement = mockMvc.perform(get("/api/v1/business-documents/" + replacementId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(replacement).get("replacementOfDocumentId").asText()).isEqualTo(originalId.toString());
        assertThat(json(replacement).get("status").asText()).isEqualTo("GENERATED");
        assertThat(jdbc.queryForObject("select status from business_documents.business_document where tenant_id=? and workspace_id=? and id=?",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), originalId)).isEqualTo("GENERATED");
        mockMvc.perform(get("/api/v1/business-documents/" + originalId + "/downloads")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    void buyerReadPermissionDoesNotAuthorizeReplacingAnIssuedDocument() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        UUID originalId = seedIssuedOrderSummary(order.id());

        mockMvc.perform(post("/api/v1/business-documents/" + originalId + "/replacements")
                        .header("Authorization", "Bearer " + accessToken(BUYER_EMAIL, "PORTAL"))
                        .header("Idempotency-Key", "buyer-replace-document-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    private UUID seedIssuedOrderSummary(String salesOrderId) {
        UUID originalId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID clientAccount = UUID.fromString(jdbc.queryForObject(
                "select client_account_id::text from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                String.class, tenant, workspace, UUID.fromString(salesOrderId)));
        Instant now = Instant.now();
        String objectKey = "documents/" + tenant + "/" + UUID.randomUUID() + ".pdf";
        byte[] content = "%PDF-1.7\nNexa issued document".getBytes(StandardCharsets.US_ASCII);
        ObjectStoragePort.StoredObject stored = storage.put(objectKey, new ByteArrayInputStream(content), content.length, "application/pdf");
        jdbc.update("insert into business_documents.object_storage_object (object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,?,?,?,?,?,?,?,?)",
                stored.objectKey(), tenant, workspace, "nexa-private", stored.checksumSha256(), stored.contentType(), stored.byteSize(), true, Timestamp.from(now));
        jdbc.update("insert into business_documents.business_document (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,storage_object_key,checksum_sha256,content_type,byte_size,generated_at,created_at,updated_at) values (?,?,?,?,? ,? ,?,1,'GENERATED','PDF',?,?,?,?,?,?,?)",
                originalId, tenant, workspace, clientAccount, "SALES_ORDER", UUID.fromString(salesOrderId), "ORDER_SUMMARY",
                stored.objectKey(), stored.checksumSha256(), stored.contentType(), stored.byteSize(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return originalId;
    }
}
