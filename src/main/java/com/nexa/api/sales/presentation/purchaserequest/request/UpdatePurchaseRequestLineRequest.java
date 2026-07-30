package com.nexa.api.sales.presentation.purchaserequest.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdatePurchaseRequestLineRequest(@NotNull @Positive BigDecimal quantity, @Size(max = 2000) String notes) { }
