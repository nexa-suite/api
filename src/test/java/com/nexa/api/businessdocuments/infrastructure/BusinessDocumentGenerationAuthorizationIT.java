package com.nexa.api.businessdocuments.infrastructure;

import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BusinessDocumentGenerationAuthorizationIT extends NexaWorkflowIntegrationSupport {
    private static final String ORDER_SUMMARY_REQUEST = """
            {"subjectType":"SALES_ORDER","subjectId":"%s","documentType":"ORDER_SUMMARY","format":"PDF"}
            """;

    @Autowired
    private ObjectStoragePort storage;

    @Autowired
    private ResolveCurrentAccessContextUseCase accessContext;

    @Test
    void buyerReadPermissionDoesNotAuthorizeGenerationAndCreatesNoRowsOrOutboxEvent() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        DocumentCounts before = counts(order.id());

        mockMvc.perform(post("/api/v1/business-document-generation-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "buyer-document-request-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_SUMMARY_REQUEST.formatted(order.id())))
                .andExpect(status().isForbidden());

        assertThat(counts(order.id())).isEqualTo(before);
    }

    @Test
    void buyerReadPermissionDoesNotAuthorizeRegenerationAndCreatesNoRowsOrOutboxEvent() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        UUID issuedDocumentId = seedIssuedOrderSummary(order.id());
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        DocumentCounts before = counts(order.id());

        mockMvc.perform(post("/api/v1/business-documents/" + issuedDocumentId + "/regenerations")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "buyer-document-regenerate-" + UUID.randomUUID()))
                .andExpect(status().isForbidden());

        assertThat(counts(order.id())).isEqualTo(before);
    }

    @Test
    void readPermissionAllowsDocumentMetadataButNotBytesWithoutDownloadPermission() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        UUID documentId = seedIssuedOrderSummary(order.id());

        mockMvc.perform(get("/api/v1/business-documents/" + documentId)
                        .header("Authorization", "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/business-documents/" + documentId + "/downloads")
                        .header("Authorization", "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/business-documents/" + documentId)
                        .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/business-documents/" + documentId + "/downloads")
                        .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                .andExpect(status().isOk());

        UUID logisticsMembership = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        List<RoleAssignment> existingRoles = jdbc.query(
                "select role_id,assigned_by_membership_id,assigned_at from tenant_management.membership_role_definition where membership_id=?",
                (rs, row) -> new RoleAssignment(rs.getObject("role_id", UUID.class),
                        rs.getObject("assigned_by_membership_id", UUID.class), rs.getTimestamp("assigned_at")),
                logisticsMembership);
        UUID downloadOnlyRole = UUID.randomUUID();
        try {
            jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", logisticsMembership);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into tenant_management.role_definition "
                            + "(id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version) "
                            + "values (?,?,?,?,?,?,'CUSTOM','ACTIVE',?,?,?,0)",
                    downloadOnlyRole, tenant, workspace, "download_only_" + downloadOnlyRole.toString().replace("-", ""),
                    "Document download only", "Test-only custom role with no metadata permission",
                    logisticsMembership, now, now);
            jdbc.update("insert into tenant_management.role_permission(role_id,permission_key) values (?,?)",
                    downloadOnlyRole, PermissionKey.DOCUMENT_DOWNLOAD.code());
            jdbc.update("insert into tenant_management.membership_role_definition "
                            + "(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id,assigned_at) "
                            + "values (?,?,?,?,?,?)",
                    logisticsMembership, tenant, workspace, downloadOnlyRole, logisticsMembership, now);

            String downloadOnly = accessToken(LOGISTICS_EMAIL, "PLATFORM");
            mockMvc.perform(get("/api/v1/business-documents/" + documentId)
                            .header("Authorization", "Bearer " + downloadOnly))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/business-documents/" + documentId + "/downloads")
                            .header("Authorization", "Bearer " + downloadOnly))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", logisticsMembership);
            jdbc.update("delete from tenant_management.role_definition where id=?", downloadOnlyRole);
            for (RoleAssignment assignment : existingRoles) {
                jdbc.update("insert into tenant_management.membership_role_definition "
                                + "(membership_id,tenant_id,workspace_id,role_id,assigned_by_membership_id,assigned_at) "
                                + "values (?,?,?,?,?,?)",
                        logisticsMembership, tenant, workspace, assignment.roleId(),
                        assignment.assignedByMembershipId(), assignment.assignedAt());
            }
        }
    }

    @Test
    void ownerCanStillRequestManualDocumentGeneration() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        DocumentCounts before = counts(order.id());

        var response = mockMvc.perform(post("/api/v1/business-document-generation-requests")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "owner-document-request-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_SUMMARY_REQUEST.formatted(order.id())))
                .andExpect(status().isAccepted())
                .andReturn();

        var body = json(response).get("documentId");
        assertThat(body).isNotNull();
        UUID documentId = UUID.fromString(body.asText());
        assertThat(json(response).get("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from business_documents.document_generation_request "
                        + "where tenant_id=? and workspace_id=? and document_id=? and requested_by_membership_id=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), documentId,
                UUID.fromString(membershipId(OWNER_EMAIL)))).isEqualTo(1);

        DocumentCounts after = counts(order.id());
        assertThat(after.documents() - before.documents()).isEqualTo(1);
        assertThat(after.requests() - before.requests()).isEqualTo(1);
        assertThat(after.outboxEvents() - before.outboxEvents()).isEqualTo(1);
    }

    @Test
    void resolvedSystemWorkflowContextRetainsDocumentGenerationPermission() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID user = jdbc.queryForObject("select id from iam.user_account where normalized_email=?",
                UUID.class, "nexa-automation@system.invalid");

        RlsRequestScope.set(tenant, workspace);
        try {
            CurrentAccessContext context = accessContext.resolve(new CurrentAccessRequest(
                    new UserId(user), new TenantId(tenant), new WorkspaceId(workspace), Surface.PLATFORM));

            assertThat(context.roleCodes()).contains("system_workflow");
            assertThat(context.allows(PermissionKey.DOCUMENT_GENERATE)).isTrue();
            assertThatCode(() -> context.requirePermission(PermissionKey.DOCUMENT_GENERATE))
                    .doesNotThrowAnyException();
        } finally {
            RlsRequestScope.clear();
        }
    }

    private DocumentCounts counts(String salesOrderId) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID subject = UUID.fromString(salesOrderId);
        int documents = jdbc.queryForObject("select count(*) from business_documents.business_document "
                        + "where tenant_id=? and workspace_id=? and subject_type='SALES_ORDER' and subject_id=? and document_type='ORDER_SUMMARY'",
                Integer.class, tenant, workspace, subject);
        int requests = jdbc.queryForObject("select count(*) from business_documents.document_generation_request "
                        + "where tenant_id=? and workspace_id=? and subject_type='SALES_ORDER' and subject_id=? and document_type='ORDER_SUMMARY'",
                Integer.class, tenant, workspace, subject);
        int outboxEvents = jdbc.queryForObject("select count(*) from integration.outbox_event "
                        + "where tenant_id=? and workspace_id=? and event_type='BUSINESS_DOCUMENT_GENERATION_REQUESTED' "
                        + "and payload->>'subjectId'=?",
                Integer.class, tenant, workspace, subject.toString());
        return new DocumentCounts(documents, requests, outboxEvents);
    }

    private UUID seedIssuedOrderSummary(String salesOrderId) {
        UUID documentId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID clientAccount = UUID.fromString(jdbc.queryForObject(
                "select client_account_id::text from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                String.class, tenant, workspace, UUID.fromString(salesOrderId)));
        Instant now = Instant.now();
        String objectKey = "documents/" + tenant + "/" + UUID.randomUUID() + ".pdf";
        byte[] content = "%PDF-1.7\nNexa issued document".getBytes(StandardCharsets.US_ASCII);
        ObjectStoragePort.StoredObject stored = storage.put(objectKey, new ByteArrayInputStream(content), content.length,
                "application/pdf");
        jdbc.update("insert into business_documents.object_storage_object "
                        + "(object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?)",
                stored.objectKey(), tenant, workspace, "nexa-private", stored.checksumSha256(), stored.contentType(),
                stored.byteSize(), true, Timestamp.from(now));
        jdbc.update("insert into business_documents.business_document "
                        + "(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,storage_object_key,checksum_sha256,content_type,byte_size,generated_at,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,1,'GENERATED','PDF',?,?,?,?,?,?,?)",
                documentId, tenant, workspace, clientAccount, "SALES_ORDER", UUID.fromString(salesOrderId),
                "ORDER_SUMMARY", stored.objectKey(), stored.checksumSha256(), stored.contentType(), stored.byteSize(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return documentId;
    }

    private record DocumentCounts(int documents, int requests, int outboxEvents) { }

    private record RoleAssignment(UUID roleId, UUID assignedByMembershipId, Timestamp assignedAt) { }
}
