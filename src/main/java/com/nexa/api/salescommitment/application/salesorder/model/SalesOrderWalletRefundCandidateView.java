package com.nexa.api.salescommitment.application.salesorder.model;

import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.util.UUID;

@NamedInterface("sales-public")
public record SalesOrderWalletRefundCandidateView(UUID salesOrderId, String orderNumber, String currency,
        BigDecimal consumedPEN, BigDecimal refundedPEN, BigDecimal refundablePEN) { }
