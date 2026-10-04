package com.nexa.api.businessdocuments.infrastructure;

import com.nexa.api.businessdocuments.application.port.BusinessDocumentPort;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirms an explicitly disabled scanner fails closed at the evidence lifecycle boundary. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.clamav.mode=disabled")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessDocumentDisabledScannerIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private BusinessDocumentPort documents;

    @Autowired
    private ResolveCurrentAccessContextUseCase accessContext;

    @Test
    void disabledScannerRejectsEvidenceInsteadOfMakingItAvailable() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        byte[] content = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");

        MvcResult result = mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "proof.pdf", "application/pdf", content))
                        .param("subjectType", "SALES_ORDER")
                        .param("subjectId", order.id())
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "disabled-scanner-" + uuid()))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(json(result).get("lifecycleStatus").asText()).isEqualTo("REJECTED");
        assertThat(json(result).get("failureCode").asText()).isEqualTo("MALWARE_SCANNER_DISABLED");
        String evidenceId = json(result).get("id").asText();
        assertThat(jdbc.queryForObject("select lifecycle_status from business_documents.evidence_object where id=?",
                String.class, java.util.UUID.fromString(evidenceId))).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select next_scan_at from business_documents.evidence_object where id=?",
                java.sql.Timestamp.class, java.util.UUID.fromString(evidenceId))).isNull();

        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        RlsRequestScope.set(tenant, workspace);
        try {
            UUID user = jdbc.queryForObject("select id from iam.user_account where normalized_email=?",
                    UUID.class, OWNER_EMAIL);
            CurrentAccessContext context = accessContext.resolve(new CurrentAccessRequest(
                    new UserId(user), new TenantId(tenant), new WorkspaceId(workspace), Surface.PLATFORM));
            assertThatThrownBy(() -> documents.downloadEvidence(context, UUID.fromString(evidenceId)))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasMessage("Evidence is not available");
        } finally {
            RlsRequestScope.clear();
        }
    }
}
