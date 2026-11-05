package com.nexa.api.customerbuyerrelationships.presentation.communication.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Plain text only; clients must render the body as text. */
public record SendBuyerSalesMessageRequest(@NotBlank @Size(max = 4000) String body) { }
