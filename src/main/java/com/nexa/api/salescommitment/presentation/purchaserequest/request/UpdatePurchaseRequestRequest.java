package com.nexa.api.salescommitment.presentation.purchaserequest.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdatePurchaseRequestRequest(
		@Size(max = 32) String priority,
		@FutureOrPresent LocalDate requestedDeliveryDate,
		@Size(max = 2000) String deliveryProfileSnapshot,
		@Size(max = 80) String paymentOption,
		@Size(max = 2000) String comment) { }
