package com.nexa.api.salescommitment.presentation.purchaserequest.response;

import java.time.LocalDate;

public record PurchaseRequestSummaryResponse(String id, String code, String clientAccountId, String status,
		String priority, LocalDate requestedDeliveryDate, long lineCount, long version) { }
