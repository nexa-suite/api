package com.nexa.api.customerbuyerrelationships.presentation.communication.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A Sales message is addressed to one Buyer membership associated with the account. */
public record SendClientAccountBuyerSalesMessageRequest(@NotNull UUID recipientBuyerMembershipId,
                                                        @NotBlank @Size(max = 4000) String body) { }
