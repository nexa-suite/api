package com.nexa.api.businessdocuments.application.publicapi;

import java.time.Instant;
import java.util.UUID;

/** BC-09 command boundary for durable document-generation requests raised by other contexts. */
public interface BusinessDocumentCommands {
    void enqueuePaymentReceipt(UUID tenantId, UUID workspaceId, UUID paymentId,
                                UUID clientAccountId, UUID requestedByMembershipId,
                                String eventKey, Instant now);
}
