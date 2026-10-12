package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.math.BigDecimal;
import java.util.UUID;

public record SalesOrderWalletRefundResponse(UUID salesOrderId, UUID refundId, UUID ledgerEntryId,
        BigDecimal amountPEN, String currency, String status) { }
