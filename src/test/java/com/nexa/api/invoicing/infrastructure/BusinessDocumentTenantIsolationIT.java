package com.nexa.api.invoicing.infrastructure;

import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BusinessDocumentTenantIsolationIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private ObjectStoragePort storage;

    @Test
    void currentTenantCannotReadOrDownloadForeignDocument() throws Exception {
        ForeignDocument foreign = createForeignDocument();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");

        mockMvc.perform(get("/api/v1/business-documents/" + foreign.documentId())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/business-documents/" + foreign.documentId() + "/downloads")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    private ForeignDocument createForeignDocument() {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        Instant now = Instant.now();
        String suffix = tenant.toString().replace("-", "").substring(0, 12);
        String objectKey = "documents/" + tenant + "/" + UUID.randomUUID() + ".pdf";
        byte[] content = "%PDF-1.7\nforeign document".getBytes(StandardCharsets.US_ASCII);

        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                tenant, "Foreign document tenant", "foreign-document-" + suffix, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                workspace, tenant, "Foreign document workspace", "foreign-document-" + suffix, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                client, tenant, workspace, "FD-" + suffix, "Foreign Document Client", "Foreign Document Client", "PE", "RUC",
                "20" + suffix + "000000", "B2B", "Foreign Contact", "foreign-document@example.test", "+51000000000", "Lima", "NET_30", Timestamp.from(now), Timestamp.from(now));

        ObjectStoragePort.StoredObject stored = storage.put(objectKey, new ByteArrayInputStream(content), content.length, "application/pdf");
        jdbc.update("insert into business_documents.object_storage_object (object_key,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,?,?,?,?,?,?)",
                stored.objectKey(), "nexa-private", stored.checksumSha256(), stored.contentType(), stored.byteSize(), true, Timestamp.from(now));
        jdbc.update("insert into business_documents.business_document (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,storage_object_key,checksum_sha256,content_type,byte_size,generated_at,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                document, tenant, workspace, client, "SALES_ORDER", UUID.randomUUID(), "ORDER_SUMMARY", 1, "GENERATED", "PDF",
                stored.objectKey(), stored.checksumSha256(), stored.contentType(), stored.byteSize(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return new ForeignDocument(document);
    }

    private record ForeignDocument(UUID documentId) { }
}
