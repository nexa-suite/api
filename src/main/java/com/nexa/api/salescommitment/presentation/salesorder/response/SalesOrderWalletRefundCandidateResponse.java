package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.math.BigDecimal;

public record SalesOrderWalletRefundCandidateResponse(String salesOrderId, String orderNumber, String currency,
        BigDecimal consumedPEN, BigDecimal refundedPEN, BigDecimal refundablePEN) { }
