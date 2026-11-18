package com.nexa.api.businessdocuments.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.port.ContentScannerPort;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort;
import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.businessdocuments.application.publicapi.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.DocumentSubjectLookupPort;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectSnapshot;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectType;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessDocumentServiceBuyerWarehouseAuthorizationTests {
    @Test
    void buyerRelationshipFenceRunsBeforeWarehouseGrantForEvidenceRead() throws Exception {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        assertThat(buyer.allows(PermissionKey.DOCUMENT_READ)).isTrue();

        UUID warehouseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        DocumentSubjectLookupPort subjects = mock(DocumentSubjectLookupPort.class);
        ResultSet resultSet = mock(ResultSet.class);
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        when(resultSet.getObject("id", UUID.class)).thenReturn(evidenceId);
        when(resultSet.getObject("client_account_id", UUID.class)).thenReturn(null);
        when(resultSet.getString("subject_type")).thenReturn(DocumentSubjectType.WAREHOUSE.name());
        when(resultSet.getObject("subject_id", UUID.class)).thenReturn(warehouseId);
        when(resultSet.getObject("requested_by_membership_id", UUID.class)).thenReturn(buyer.membershipId().value());
        when(resultSet.getString("lifecycle_status")).thenReturn("AVAILABLE");
        when(resultSet.getString("declared_content_type")).thenReturn("image/png");
        when(resultSet.getString("detected_content_type")).thenReturn("image/png");
        when(resultSet.getString("original_filename")).thenReturn("evidence.png");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        DocumentSubjectReference subjectReference = new DocumentSubjectReference(
                DocumentSubjectType.WAREHOUSE, warehouseId.toString());
        when(subjects.lookup(eq(buyer.tenantId().toString()), eq(buyer.workspaceId().toString()),
                eq(buyer.membershipId().value().toString()), eq(subjectReference)))
                .thenReturn(new DocumentSubjectSnapshot(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                        DocumentSubjectType.WAREHOUSE, warehouseId.toString(), null, "ACTIVE", true));
        doReturn(true).when(warehouseAccess).hasActiveGrant(buyer, warehouseId);

        BusinessDocumentService service = new BusinessDocumentService(jdbc, mock(ObjectStoragePort.class),
                mock(ContentScannerPort.class), mock(DocumentRendererPort.class), subjects,
                mock(DocumentProjectionLookupPort.class), mock(CustomerAccountQuery.class),
                mock(WorkspaceDirectory.class), warehouseAccess, null, mock(CanonicalOutboxPort.class));

        assertThatThrownBy(() -> service.evidence(buyer, evidenceId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Evidence not found");
        verify(warehouseAccess, never()).hasActiveGrant(buyer, warehouseId);
        verify(subjects, never()).lookup(anyString(), anyString(), anyString(), any());
    }

    @Test
    void buyerWithPaymentCreateCanRequestEvidenceForTheActiveRelatedReceivable() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID receivableId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DocumentSubjectLookupPort subjects = mock(DocumentSubjectLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        DocumentSubjectReference reference = new DocumentSubjectReference(
                DocumentSubjectType.RECEIVABLE, receivableId.toString());
        when(subjects.lookup(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                buyer.membershipId().value().toString(), reference))
                .thenReturn(receivableSnapshot(buyer, receivableId, clientAccountId));
        when(accounts.findBuyerReference(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                buyer.membershipId().value().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(clientAccountId.toString(), "ACTIVE")));
        doThrow(new IllegalStateException("scoped persistence reached")).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        BusinessDocumentService service = service(jdbc, subjects, accounts);

        assertThat(buyer.allows(PermissionKey.PAYMENT_CREATE)).isTrue();
        assertThat(buyer.allows(PermissionKey.DOCUMENT_UPLOAD)).isFalse();
        assertThatThrownBy(() -> service.requestEvidence(buyer, "RECEIVABLE", receivableId,
                "transfer.png", "image/png", "buyer-transfer-proof"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scoped persistence reached");
        verify(accounts).findBuyerReference(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                buyer.membershipId().value().toString());
    }

    @Test
    void paymentCreateDoesNotAuthorizeBuyerEvidenceForOtherSubjects() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DocumentSubjectLookupPort subjects = mock(DocumentSubjectLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        BusinessDocumentService service = service(jdbc, subjects, accounts);

        assertThatThrownBy(() -> service.requestEvidence(buyer, "PAYMENT", UUID.randomUUID(),
                "transfer.png", "image/png", "buyer-payment-evidence"))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(jdbc, subjects, accounts);
    }

    @Test
    void buyerReceivableEvidenceRequiresTheExactTenantWorkspaceAndActiveAccountLink() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID receivableId = UUID.randomUUID();
        UUID activeAccountId = UUID.randomUUID();

        assertBuyerReceivableDenied(buyer,
                new DocumentSubjectSnapshot(UUID.randomUUID().toString(), buyer.workspaceId().toString(),
                        DocumentSubjectType.RECEIVABLE, receivableId.toString(), activeAccountId.toString(),
                        "OPEN", true),
                activeAccountId, "ACTIVE");
        assertBuyerReceivableDenied(buyer,
                new DocumentSubjectSnapshot(buyer.tenantId().toString(), UUID.randomUUID().toString(),
                        DocumentSubjectType.RECEIVABLE, receivableId.toString(), activeAccountId.toString(),
                        "OPEN", true),
                activeAccountId, "ACTIVE");
        assertBuyerReceivableDenied(buyer,
                receivableSnapshot(buyer, receivableId, UUID.randomUUID()), activeAccountId, "ACTIVE");
        assertBuyerReceivableDenied(buyer,
                receivableSnapshot(buyer, receivableId, activeAccountId), activeAccountId, "PENDING");
        assertBuyerReceivableDenied(buyer,
                receivableSnapshot(buyer, receivableId, activeAccountId), activeAccountId, "REVOKED");
    }

    private void assertBuyerReceivableDenied(CurrentAccessContext buyer, DocumentSubjectSnapshot snapshot,
                                               UUID linkedAccountId, String relationshipStatus) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DocumentSubjectLookupPort subjects = mock(DocumentSubjectLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        DocumentSubjectReference reference = new DocumentSubjectReference(
                DocumentSubjectType.RECEIVABLE, snapshot.subjectId());
        when(subjects.lookup(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                buyer.membershipId().value().toString(), reference)).thenReturn(snapshot);
        when(accounts.findBuyerReference(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                buyer.membershipId().value().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(linkedAccountId.toString(), relationshipStatus)));
        BusinessDocumentService service = service(jdbc, subjects, accounts);

        assertThatThrownBy(() -> service.requestEvidence(buyer, "RECEIVABLE", UUID.fromString(snapshot.subjectId()),
                "transfer.png", "image/png", "buyer-transfer-proof"))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verifyNoInteractions(jdbc);
    }

    private static DocumentSubjectSnapshot receivableSnapshot(CurrentAccessContext buyer, UUID receivableId,
                                                                UUID clientAccountId) {
        return new DocumentSubjectSnapshot(buyer.tenantId().toString(), buyer.workspaceId().toString(),
                DocumentSubjectType.RECEIVABLE, receivableId.toString(), clientAccountId.toString(), "OPEN", true);
    }

    private BusinessDocumentService service(JdbcTemplate jdbc, DocumentSubjectLookupPort subjects,
                                             CustomerAccountQuery accounts) {
        return new BusinessDocumentService(jdbc, mock(ObjectStoragePort.class), mock(ContentScannerPort.class),
                mock(DocumentRendererPort.class), subjects, mock(DocumentProjectionLookupPort.class), accounts,
                mock(WorkspaceDirectory.class), mock(WarehouseObjectAccess.class), null,
                mock(CanonicalOutboxPort.class));
    }

    private CurrentAccessContext context(MembershipRole role) {
        Membership membership = new Membership(MembershipId.random(), UserId.random(), TenantId.random(),
                WorkspaceId.random(), Set.of(role), MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), Surface.PORTAL);
    }
}
