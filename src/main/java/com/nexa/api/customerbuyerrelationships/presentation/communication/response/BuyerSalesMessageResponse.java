package com.nexa.api.customerbuyerrelationships.presentation.communication.response;

import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;

import java.time.Instant;
import java.util.UUID;

public record BuyerSalesMessageResponse(UUID id, UUID clientAccountId, UUID buyerMembershipId, UUID senderMembershipId,
                                        BuyerSalesMessage.SenderKind senderKind, String body,
                                        Instant createdAt) { }
