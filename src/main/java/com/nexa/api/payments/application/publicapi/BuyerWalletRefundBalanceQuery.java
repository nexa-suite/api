package com.nexa.api.payments.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Supplies refundable totals for Sales Orders without exposing Payments persistence. */
@NamedInterface("payments-public")
public interface BuyerWalletRefundBalanceQuery {
    Map<UUID, RefundableBalance> findRefundableBalances(CurrentAccessContext context,
            Collection<UUID> salesOrderIds);

    record RefundableBalance(BigDecimal consumedPEN, BigDecimal refundedPEN, BigDecimal refundablePEN) { }
}
