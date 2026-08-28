package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobileDeliveryContractServiceTests {
    private static final UUID TENANT = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
    private static final UUID WORKSPACE = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");
    private static final UUID MEMBERSHIP = UUID.fromString("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2");
    private static final UUID USER = UUID.fromString("c9c1f2e5-e4c1-4c9f-9b2d-98f2a40e2b21");
    private static final Instant NOW = Instant.parse("2026-08-28T15:00:00Z");

    @Test
    void issuesOpaqueHashedTokenAndDoesNotPersistTheRawValue() {
        MobileDeliveryContractPort persistence = mock(MobileDeliveryContractPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();
        when(persistence.issue(any())).thenAnswer(invocation -> {
            MobileDeliveryContractPort.IssueRequest request = invocation.getArgument(0);
            return new MobileDeliveryContractPort.HandoffIssue(handoffId, request.deliveryId(), request.attemptId(),
                    request.expiresAt(), "ACTIVE", false);
        });

        MobileDeliveryContractService.IssuedHandoff issued = service(persistence, accounts, traceability)
                .issue(context(), deliveryId, attemptId, "handoff-retry-1");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.token()).hasSizeGreaterThan(40);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        var request = org.mockito.ArgumentCaptor.forClass(MobileDeliveryContractPort.IssueRequest.class);
        verify(persistence).issue(request.capture());
        assertThat(request.getValue().tokenHash()).isEqualTo(sha256(issued.token()));
        assertThat(request.getValue().tokenHash()).doesNotContain(issued.token());
        verify(traceability).record(any(BusinessTraceabilityCommands.TraceRequest.class));
    }

    @Test
    void rejectsReceiptDecisionBeforeAnyPersistenceMutation() {
        MobileDeliveryContractPort persistence = mock(MobileDeliveryContractPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        CurrentAccessContext context = context();
        when(context.hasRole(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole.BUYER))
                .thenReturn(true);

        assertThatThrownBy(() -> service(persistence, accounts, traceability).recordReceipt(
                context, UUID.randomUUID(), "opaque-token", "OTHER", java.math.BigDecimal.ONE,
                null, "receipt-1"))
                .isInstanceOf(FulfillmentOperationException.class)
                .hasMessage("BUYER_RECEIPT_DECISION_INVALID");
        verify(persistence, never()).recordReceipt(any());
    }

    private static MobileDeliveryContractService service(MobileDeliveryContractPort persistence,
                                                          CustomerAccountQuery accounts,
                                                          BusinessTraceabilityCommands traceability) {
        return new MobileDeliveryContractService(persistence, accounts, traceability,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
    }

    private static CurrentAccessContext context() {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        when(context.tenantId()).thenReturn(new TenantId(TENANT));
        when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE));
        when(context.membershipId()).thenReturn(new MembershipId(MEMBERSHIP));
        when(context.userId()).thenReturn(new UserId(USER));
        when(context.hasRole(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole.BUYER))
                .thenReturn(false);
        return context;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
