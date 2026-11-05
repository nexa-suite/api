package com.nexa.api.customerbuyerrelationships.application.port.out;

import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL operations for tenant-local Buyer and assigned Sales account messages. */
@org.springframework.modulith.NamedInterface("tenant-database-binding")
public interface BuyerSalesMessagePersistencePort {
    Optional<UUID> findActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId);

    /** Locks the active Buyer relationship and Client Account until this transaction completes. */
    Optional<UUID> lockActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId);

    boolean accountExists(UUID tenantId, UUID workspaceId, UUID clientAccountId);

    boolean accountAssignedToSalesMembership(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                             UUID salesMembershipId);

    /** Locks the current Sales assignment until this transaction completes. */
    boolean lockAssignedSalesAccount(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                     UUID salesMembershipId, UUID recipientBuyerMembershipId);

    Optional<Instant> messageCreatedAt(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                       UUID buyerMembershipId, UUID messageId);

    List<BuyerSalesMessage> findMessages(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                         UUID buyerMembershipId, Instant afterCreatedAt, UUID afterMessageId,
                                         int limit);

    boolean insertIfAbsent(UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID messageId,
                           UUID senderMembershipId, BuyerSalesMessage.SenderKind senderKind,
                           UUID buyerMembershipId, String idempotencyKey, String requestHash, String body);

    Optional<StoredMessage> findByIdempotencyKey(UUID tenantId, UUID workspaceId,
                                                 UUID senderMembershipId, String idempotencyKey);

    @org.springframework.modulith.NamedInterface(value = "tenant-database-binding", propagate = false)
    record StoredMessage(BuyerSalesMessage message, String requestHash) { }
}
