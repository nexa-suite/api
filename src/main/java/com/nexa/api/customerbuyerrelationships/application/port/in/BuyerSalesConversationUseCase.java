package com.nexa.api.customerbuyerrelationships.application.port.in;

import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessagePage;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Plain-text account messages between an active Buyer and its assigned Sales membership. */
@org.springframework.modulith.NamedInterface("buyer-sales-conversation-usecases")
public interface BuyerSalesConversationUseCase {
    BuyerSalesMessagePage listForBuyer(CurrentAccessContext context, UUID afterMessageId, int size);

    SendResult sendForBuyer(CurrentAccessContext context, String idempotencyKey, String body);

    BuyerSalesMessagePage listForAccount(CurrentAccessContext context, String clientAccountId,
                                         UUID afterMessageId, int size);

    SendResult sendForAccount(CurrentAccessContext context, String clientAccountId,
                              UUID recipientBuyerMembershipId, String idempotencyKey, String body);

    @org.springframework.modulith.NamedInterface(value = "buyer-sales-conversation-usecases", propagate = false)
    record SendResult(BuyerSalesMessage message, boolean replayed) { }
}
