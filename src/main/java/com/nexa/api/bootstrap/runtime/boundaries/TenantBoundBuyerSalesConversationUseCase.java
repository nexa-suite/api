package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerSalesConversationUseCase;
import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerSalesMessagePersistencePort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessagePage;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountAccessPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Routes append-only account messages through verified Tenant/Workspace scope. */
public final class TenantBoundBuyerSalesConversationUseCase implements BuyerSalesConversationUseCase {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final TenantBusinessDatabaseRouter router;
    private final TenantBuyerSalesMessagePersistenceFactory messages;

    public TenantBoundBuyerSalesConversationUseCase(TenantBusinessDatabaseRouter router,
            TenantBuyerSalesMessagePersistenceFactory messages) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.messages = Objects.requireNonNull(messages, "Tenant message persistence factory is required");
    }

    @Override
    public BuyerSalesMessagePage listForBuyer(CurrentAccessContext context, UUID afterMessageId, int size) {
        authorizeBuyer(context, false);
        validatePageSize(size);
        return inTenant(context, jdbc -> {
            BuyerSalesMessagePersistencePort persistence = messages.bindTo(jdbc);
            UUID accountId = activeBuyerAccount(context, persistence);
            return page(context, persistence, accountId, context.membershipId().value(), afterMessageId, size);
        });
    }

    @Override
    public SendResult sendForBuyer(CurrentAccessContext context, String idempotencyKey, String body) {
        authorizeBuyer(context, true);
        validateMessage(idempotencyKey, body);
        return inTenant(context, jdbc -> {
            BuyerSalesMessagePersistencePort persistence = messages.bindTo(jdbc);
            UUID accountId = lockActiveBuyerAccount(context, persistence);
            return send(persistence, context, accountId, BuyerSalesMessage.SenderKind.BUYER,
                    context.membershipId().value(), context.membershipId().value(), idempotencyKey, body);
        });
    }

    @Override
    public BuyerSalesMessagePage listForAccount(CurrentAccessContext context, String clientAccountId,
                                                UUID afterMessageId, int size) {
        authorizeAccountRead(context);
        validatePageSize(size);
        UUID accountId = uuid(clientAccountId, "client-account");
        return inTenant(context, jdbc -> {
            BuyerSalesMessagePersistencePort persistence = messages.bindTo(jdbc);
            authorizeAccountReadWithinScope(context, persistence, accountId);
            return page(context, persistence, accountId, null, afterMessageId, size);
        });
    }

    @Override
    public SendResult sendForAccount(CurrentAccessContext context, String clientAccountId,
                                     UUID recipientBuyerMembershipId, String idempotencyKey, String body) {
        authorizeSalesWriter(context);
        validateMessage(idempotencyKey, body);
        Objects.requireNonNull(recipientBuyerMembershipId, "A Buyer recipient membership is required");
        UUID accountId = uuid(clientAccountId, "client-account");
        return inTenant(context, jdbc -> {
            BuyerSalesMessagePersistencePort persistence = messages.bindTo(jdbc);
            lockAssignedSalesAccount(context, persistence, accountId, recipientBuyerMembershipId);
            return send(persistence, context, accountId, BuyerSalesMessage.SenderKind.SALES,
                    context.membershipId().value(), recipientBuyerMembershipId, idempotencyKey, body);
        });
    }

    private <T> T inTenant(CurrentAccessContext context, TenantBusinessDatabaseWork<T> operation) {
        requireTenantWorkspaceScope(context);
        return router.inTransaction(context, operation);
    }

    private static BuyerSalesMessagePage page(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence, UUID accountId, UUID buyerMembershipId,
            UUID afterMessageId, int size) {
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        var cursorAt = afterMessageId == null ? Optional.<java.time.Instant>empty()
                : persistence.messageCreatedAt(tenantId, workspaceId, accountId, buyerMembershipId, afterMessageId);
        if (afterMessageId != null && cursorAt.isEmpty()) {
            throw new ApiResourceNotFoundException("buyer-sales-message-cursor");
        }
        List<BuyerSalesMessage> rows = persistence.findMessages(tenantId, workspaceId, accountId,
                buyerMembershipId, cursorAt.orElse(null), afterMessageId, size + 1);
        boolean hasMore = rows.size() > size;
        List<BuyerSalesMessage> items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        UUID next = items.isEmpty() ? afterMessageId : items.get(items.size() - 1).id();
        return new BuyerSalesMessagePage(items, next, hasMore);
    }

    private static SendResult send(BuyerSalesMessagePersistencePort persistence, CurrentAccessContext context,
            UUID accountId, BuyerSalesMessage.SenderKind senderKind, UUID senderMembershipId,
            UUID buyerMembershipId, String idempotencyKey, String body) {
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        String requestHash = requestHash(accountId, senderKind, senderMembershipId, buyerMembershipId, body);
        UUID messageId = UUID.randomUUID();
        boolean inserted = persistence.insertIfAbsent(tenantId, workspaceId, accountId, messageId,
                senderMembershipId, senderKind, buyerMembershipId, idempotencyKey, requestHash, body);
        BuyerSalesMessagePersistencePort.StoredMessage stored = persistence.findByIdempotencyKey(
                        tenantId, workspaceId, senderMembershipId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Persisted Buyer and Sales message is unavailable"));
        boolean sameCommand = stored.message().clientAccountId().equals(accountId)
                && stored.message().senderKind() == senderKind
                && stored.message().buyerMembershipId().equals(buyerMembershipId)
                && MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.US_ASCII),
                        requestHash.getBytes(StandardCharsets.US_ASCII));
        if (!sameCommand) throw new CustomerRelationshipConflictException();
        return new SendResult(stored.message(), !inserted);
    }

    private static UUID activeBuyerAccount(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence) {
        return persistence.findActiveBuyerAccount(context.tenantId().value(), context.workspaceId().value(),
                        context.membershipId().value())
                .orElseThrow(() -> new AccessPolicyViolation("An active Buyer account is required"));
    }

    private static UUID lockActiveBuyerAccount(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence) {
        return persistence.lockActiveBuyerAccount(context.tenantId().value(), context.workspaceId().value(),
                        context.membershipId().value())
                .orElseThrow(() -> new AccessPolicyViolation("An active Buyer account is required"));
    }

    private static void authorizeBuyer(CurrentAccessContext context, boolean write) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer membership is required for the Buyer message stream");
        }
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(write ? PermissionKey.BUYER_SALES_WRITE : PermissionKey.BUYER_SALES_READ);
    }

    private static void authorizeAccountRead(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer messages use the current Buyer account route");
        }
        context.requireSurface(Surface.PLATFORM);
        if (!context.hasRole(MembershipRole.SALES)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)) {
            throw new AccessPolicyViolation("Sales, Company Owner, or Business Operations Manager access is required");
        }
        ClientAccountAccessPolicy.requireRead(context);
    }

    private static void authorizeSalesWriter(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        if (!context.hasRole(MembershipRole.SALES)
                || context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            throw new AccessPolicyViolation("Only the assigned Sales membership can send account messages");
        }
        context.requireSurface(Surface.PLATFORM);
        context.requirePermission(PermissionKey.CLIENT_MANAGE);
    }

    private static void authorizeAccountReadWithinScope(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence, UUID accountId) {
        if (ClientAccountAccessPolicy.isSalesOwnerScoped(context)) {
            requireAssignedSalesAccount(context, persistence, accountId);
            return;
        }
        if (!persistence.accountExists(context.tenantId().value(), context.workspaceId().value(), accountId)) {
            throw new ApiResourceNotFoundException("client-account");
        }
    }

    private static void requireAssignedSalesAccount(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence, UUID accountId) {
        if (!persistence.accountAssignedToSalesMembership(context.tenantId().value(),
                context.workspaceId().value(), accountId, context.membershipId().value())) {
            throw new ApiResourceNotFoundException("client-account");
        }
    }

    private static void lockAssignedSalesAccount(CurrentAccessContext context,
            BuyerSalesMessagePersistencePort persistence, UUID accountId, UUID buyerMembershipId) {
        if (!persistence.lockAssignedSalesAccount(context.tenantId().value(), context.workspaceId().value(),
                accountId, context.membershipId().value(), buyerMembershipId)) {
            throw new ApiResourceNotFoundException("client-account-buyer-membership");
        }
    }

    private static void requireTenantWorkspaceScope(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        if (context.tenantId() == null || context.workspaceId() == null || context.membershipId() == null) {
            throw new AccessPolicyViolation("Tenant, Workspace, and membership scope are required");
        }
    }

    private static void validatePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Message page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static void validateMessage(String idempotencyKey, String body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 128 printable characters");
        }
        if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Message body must contain 1 to 4000 characters");
        }
    }

    private static UUID uuid(String value, String resource) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("A valid " + resource + " id is required", invalid);
        }
    }

    private static String requestHash(UUID accountId, BuyerSalesMessage.SenderKind senderKind,
                                      UUID senderMembershipId, UUID buyerMembershipId, String body) {
        String source = accountId + "\n" + senderKind.name() + "\n" + senderMembershipId + "\n"
                + buyerMembershipId + "\n" + body;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
