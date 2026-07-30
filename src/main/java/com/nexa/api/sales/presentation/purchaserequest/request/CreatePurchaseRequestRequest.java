package com.nexa.api.sales.presentation.purchaserequest.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreatePurchaseRequestRequest(
		@Size(max = 64) String clientAccountId,
		@Size(max = 32) String priority,
		@FutureOrPresent LocalDate requestedDeliveryDate,
		@Size(max = 2000) String deliveryProfileSnapshot,
		@Size(max = 80) String paymentOption,
		@Size(max = 2000) String comment,
		@NotEmpty @Valid List<PurchaseRequestLineRequest> lines) { }
