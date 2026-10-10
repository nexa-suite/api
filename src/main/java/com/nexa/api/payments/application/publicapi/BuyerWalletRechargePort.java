package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** Trusted Tenant-bound BC-08 recharge composition. Implementations never fall back to central business tables. */
public interface BuyerWalletRechargePort {
    BuyerWalletModels.RechargeIntentView create(CurrentAccessContext context, BigDecimal amountPEN,
                                                 String idempotencyKey);

    BuyerWalletModels.RechargeView get(CurrentAccessContext context, UUID rechargeId);
}
