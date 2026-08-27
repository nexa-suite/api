package com.nexa.api.salescommitment.application.purchaserequest.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.exception.PurchaseRequestExpiredException;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseRequestExpiryBoundaryTests {
    private static final Instant BOUNDARY = Instant.parse("2026-08-25T22:00:00Z");
    private static final String REQUEST_ID = "4b3a5b3c-80cf-41ec-8b3d-1f1d2c4e5f60";
    private static final String TENANT_ID = "3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10";
    private static final String WORKSPACE_ID = "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17";
    private static final String MEMBERSHIP_ID = "24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2";

    @Test
    void nowEqualToExpiresAtMaterializesExpiryAndReleasesCommitment() {
        PurchaseRequestPersistencePort persistence = mock(PurchaseRequestPersistencePort.class);
        PurchaseRequestEventPersistencePort events = mock(PurchaseRequestEventPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        CatalogItemSnapshotLookupPort catalog = mock(CatalogItemSnapshotLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        ChangeEventPersistencePort changeFeed = mock(ChangeEventPersistencePort.class);
        CommercialCommitmentPort commitments = mock(CommercialCommitmentPort.class);
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        PurchaseRequestView current = new PurchaseRequestView(REQUEST_ID, "PR-BOUNDARY", "client", MEMBERSHIP_ID,
                "APPROVED", "NORMAL", LocalDate.of(2026, 8, 25), "delivery", "IMMEDIATE", "comment", null,
                List.of(), 7, BOUNDARY);

        when(context.tenantId()).thenReturn(new TenantId(TENANT_ID));
        when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE_ID));
        when(context.membershipId()).thenReturn(new MembershipId(MEMBERSHIP_ID));
        when(context.hasRole(any())).thenReturn(false);
        when(persistence.find(anyString(), anyString(), any(), anyString())).thenReturn(Optional.of(current));
        when(persistence.transition(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyLong())).thenReturn(1);

        PurchaseRequestService service = new PurchaseRequestService(persistence, events, idempotency, catalog, accounts,
                changeFeed, commitments, Clock.fixed(BOUNDARY, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.transition(context, REQUEST_ID, "cancel", null, 7, null))
                .isInstanceOf(PurchaseRequestExpiredException.class);

        verify(persistence).transition(TENANT_ID, WORKSPACE_ID, null, REQUEST_ID, "APPROVED", "EXPIRED",
                "Business expiry", MEMBERSHIP_ID, 7);
        verify(commitments).releaseForPurchaseRequest(java.util.UUID.fromString(TENANT_ID),
                java.util.UUID.fromString(WORKSPACE_ID), java.util.UUID.fromString(REQUEST_ID), "EXPIRED");
    }
}
