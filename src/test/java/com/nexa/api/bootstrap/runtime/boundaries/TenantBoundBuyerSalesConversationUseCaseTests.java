package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerSalesMessagePersistencePort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantBoundBuyerSalesConversationUseCaseTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();

    @Test
    void buyerCannotAddressAnArbitrarySalesAccount() {
        TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
        TenantBuyerSalesMessagePersistenceFactory persistence = mock(TenantBuyerSalesMessagePersistenceFactory.class);
        var useCase = new TenantBoundBuyerSalesConversationUseCase(router, persistence);

        assertThatThrownBy(() -> useCase.listForAccount(context(MembershipRole.BUYER), UUID.randomUUID().toString(),
                null, 50)).isInstanceOf(AccessPolicyViolation.class);
        assertThatThrownBy(() -> useCase.sendForAccount(context(MembershipRole.BUYER), UUID.randomUUID().toString(),
                UUID.randomUUID(), "buyer-key", "hello")).isInstanceOf(AccessPolicyViolation.class);

        verifyNoInteractions(router, persistence);
    }

    @Test
    void buyerMessageWriteLocksAndUsesOnlyItsActiveAccount() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID accountId = UUID.randomUUID();
        String idempotencyKey = "buyer-message-key";
        String body = "Please confirm the delivery window";
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-21T15:30:00Z");
        BuyerSalesMessage message = new BuyerSalesMessage(messageId, accountId, buyer.membershipId().value(),
                buyer.membershipId().value(),
                BuyerSalesMessage.SenderKind.BUYER, body, createdAt);

        Fixture fixture = fixture(buyer);
        when(fixture.persistence.lockActiveBuyerAccount(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value()))
                .thenReturn(Optional.of(accountId));
        when(fixture.persistence.insertIfAbsent(eq(TENANT_ID), eq(WORKSPACE_ID), eq(accountId), any(UUID.class),
                eq(buyer.membershipId().value()), eq(BuyerSalesMessage.SenderKind.BUYER),
                eq(buyer.membershipId().value()), eq(idempotencyKey), eq(requestHash(accountId,
                        BuyerSalesMessage.SenderKind.BUYER, buyer.membershipId().value(),
                        buyer.membershipId().value(), body)), eq(body)))
                .thenReturn(false);
        when(fixture.persistence.findByIdempotencyKey(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value(),
                idempotencyKey)).thenReturn(Optional.of(new BuyerSalesMessagePersistencePort.StoredMessage(
                        message, requestHash(accountId, BuyerSalesMessage.SenderKind.BUYER,
                                buyer.membershipId().value(), buyer.membershipId().value(), body))));

        var result = fixture.useCase.sendForBuyer(buyer, idempotencyKey, body);

        assertThat(result.message()).isEqualTo(message);
        assertThat(result.replayed()).isTrue();
        verify(fixture.persistence).lockActiveBuyerAccount(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value());
        verify(fixture.persistence, never()).findActiveBuyerAccount(any(), any(), any());
        verify(fixture.persistence).insertIfAbsent(eq(TENANT_ID), eq(WORKSPACE_ID), eq(accountId), any(UUID.class),
                eq(buyer.membershipId().value()), eq(BuyerSalesMessage.SenderKind.BUYER),
                eq(buyer.membershipId().value()), eq(idempotencyKey), any(), eq(body));
    }

    @Test
    void salesCannotSendAfterAccountAssignmentHasMoved() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        UUID accountId = UUID.randomUUID();
        UUID buyerMembershipId = UUID.randomUUID();
        Fixture fixture = fixture(sales);
        when(fixture.persistence.lockAssignedSalesAccount(TENANT_ID, WORKSPACE_ID, accountId,
                sales.membershipId().value(), buyerMembershipId)).thenReturn(false);

        assertThatThrownBy(() -> fixture.useCase.sendForAccount(sales, accountId.toString(), buyerMembershipId,
                "sales-key", "hello"))
                .isInstanceOf(ApiResourceNotFoundException.class);

        verify(fixture.persistence).lockAssignedSalesAccount(TENANT_ID, WORKSPACE_ID, accountId,
                sales.membershipId().value(), buyerMembershipId);
        verify(fixture.persistence, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void bomCanReadAccountThreadThroughExistingReadPolicyButCannotSend() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        UUID accountId = UUID.randomUUID();
        Fixture fixture = fixture(bom);
        when(fixture.persistence.accountExists(TENANT_ID, WORKSPACE_ID, accountId)).thenReturn(true);
        when(fixture.persistence.findMessages(TENANT_ID, WORKSPACE_ID, accountId, null, null, null, 51))
                .thenReturn(List.of());

        var page = fixture.useCase.listForAccount(bom, accountId.toString(), null, 50);
        assertThat(page.items()).isEmpty();

        assertThatThrownBy(() -> fixture.useCase.sendForAccount(bom, accountId.toString(), UUID.randomUUID(),
                "bom-key", "hello"))
                .isInstanceOf(AccessPolicyViolation.class);
        verify(fixture.persistence, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void reusingAnIdempotencyKeyForAnotherBodyReturnsConflict() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID accountId = UUID.randomUUID();
        Fixture fixture = fixture(buyer);
        when(fixture.persistence.lockActiveBuyerAccount(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value()))
                .thenReturn(Optional.of(accountId));
        when(fixture.persistence.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        String originalBody = "original message";
        when(fixture.persistence.findByIdempotencyKey(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value(), "same-key"))
                .thenReturn(Optional.of(new BuyerSalesMessagePersistencePort.StoredMessage(
                        new BuyerSalesMessage(UUID.randomUUID(), accountId, buyer.membershipId().value(),
                                buyer.membershipId().value(), BuyerSalesMessage.SenderKind.BUYER, originalBody,
                                Instant.now()),
                        requestHash(accountId, BuyerSalesMessage.SenderKind.BUYER,
                                buyer.membershipId().value(), buyer.membershipId().value(), originalBody))));

        assertThatThrownBy(() -> fixture.useCase.sendForBuyer(buyer, "same-key", "different message"))
                .isInstanceOf(CustomerRelationshipConflictException.class);
    }

    @Test
    void buyerReadFiltersTheCurrentMembershipInsideItsActiveAccount() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID accountId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-21T15:30:00Z");
        UUID buyerMembershipId = buyer.membershipId().value();
        BuyerSalesMessage message = new BuyerSalesMessage(messageId, accountId, buyerMembershipId,
                UUID.randomUUID(), BuyerSalesMessage.SenderKind.SALES, "Reply for this Buyer", createdAt);
        Fixture fixture = fixture(buyer);
        when(fixture.persistence.findActiveBuyerAccount(TENANT_ID, WORKSPACE_ID, buyerMembershipId))
                .thenReturn(Optional.of(accountId));
        when(fixture.persistence.findMessages(TENANT_ID, WORKSPACE_ID, accountId, buyerMembershipId,
                null, null, 51)).thenReturn(List.of(message));

        var page = fixture.useCase.listForBuyer(buyer, null, 50);

        assertThat(page.items()).containsExactly(message);
        verify(fixture.persistence).findMessages(TENANT_ID, WORKSPACE_ID, accountId, buyerMembershipId,
                null, null, 51);
        verify(fixture.persistence, never()).findMessages(TENANT_ID, WORKSPACE_ID, accountId, null,
                null, null, 51);
    }

    @Test
    void buyerCannotListMessagesAfterItsAccountRelationshipIsRevoked() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        Fixture fixture = fixture(buyer);
        when(fixture.persistence.findActiveBuyerAccount(TENANT_ID, WORKSPACE_ID, buyer.membershipId().value()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.useCase.listForBuyer(buyer, null, 50))
                .isInstanceOf(AccessPolicyViolation.class);
        verify(fixture.persistence, never()).findMessages(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void malformedSalesAccountIdentifierIsAClientValidationError() {
        Fixture fixture = fixture(context(MembershipRole.SALES));
        assertThatThrownBy(() -> fixture.useCase.listForAccount(context(MembershipRole.SALES), "invalid", null, 25))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fixture.router, fixture.factory);
    }

    private static Fixture fixture(CurrentAccessContext context) {
        TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
        TenantBuyerSalesMessagePersistenceFactory factory = mock(TenantBuyerSalesMessagePersistenceFactory.class);
        BuyerSalesMessagePersistencePort persistence = mock(BuyerSalesMessagePersistencePort.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(factory.bindTo(jdbc)).thenReturn(persistence);
        doAnswer(invocation -> {
            TenantBusinessDatabaseWork<Object> work = invocation.getArgument(1);
            return work.execute(jdbc);
        }).when(router).inTransaction(eq(context), any());
        return new Fixture(new TenantBoundBuyerSalesConversationUseCase(router, factory), router, factory,
                persistence);
    }

    private static CurrentAccessContext context(MembershipRole role) {
        RoleDefinition definition = role == MembershipRole.TENANT_ADMIN || role == MembershipRole.COMPANY_OWNER
                ? RoleDefinition.systemReserved(role, Instant.EPOCH)
                : RoleDefinition.systemTemplate(role, Instant.EPOCH);
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(), 7);
        Membership membership = new Membership(MembershipId.random(), UserId.random(), new TenantId(TENANT_ID),
                new WorkspaceId(WORKSPACE_ID), Set.of(role), authorization.roleCodes(), authorization.roleDefinitionIds(),
                MembershipStatus.ACTIVE, authorization.authorizationVersion());
        Surface surface = role == MembershipRole.BUYER ? Surface.PORTAL : Surface.PLATFORM;
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }

    private static String requestHash(UUID accountId, BuyerSalesMessage.SenderKind senderKind,
                                      UUID senderMembershipId, UUID buyerMembershipId, String body) {
        String source = accountId + "\n" + senderKind.name() + "\n" + senderMembershipId + "\n"
                + buyerMembershipId + "\n" + body;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(TenantBoundBuyerSalesConversationUseCase useCase, TenantBusinessDatabaseRouter router,
                          TenantBuyerSalesMessagePersistenceFactory factory,
                          BuyerSalesMessagePersistencePort persistence) { }
}
