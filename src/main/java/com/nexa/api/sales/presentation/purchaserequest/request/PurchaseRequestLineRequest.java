package com.nexa.api.sales.presentation.purchaserequest.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PurchaseRequestLineRequest(
		@NotBlank @Size(max = 64) String catalogItemId,
		@NotNull @Positive BigDecimal quantity,
		@NotBlank @Size(max = 32) String unit,
		@Size(max = 2000) String notes) { }
