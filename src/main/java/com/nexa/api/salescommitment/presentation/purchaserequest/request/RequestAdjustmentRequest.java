package com.nexa.api.salescommitment.presentation.purchaserequest.request;

import jakarta.validation.constraints.Size;

public record RequestAdjustmentRequest(@Size(max = 2000) String reviewNote) { }
