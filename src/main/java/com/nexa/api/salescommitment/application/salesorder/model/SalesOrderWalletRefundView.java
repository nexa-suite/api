package com.nexa.api.salescommitment.application.salesorder.model;

import java.math.BigDecimal;
import java.util.UUID;

public record SalesOrderWalletRefundView(UUID salesOrderId, UUID refundId, UUID ledgerEntryId,
        BigDecimal amountPEN, String currency, String status) { }
