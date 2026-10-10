package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** BC-08 JDBC commands bound to one Tenant transaction supplied by trusted runtime composition. */
public interface BuyerWalletRechargeTenantCommands {
    Claim prepare(CurrentAccessContext context, BigDecimal amountPEN, String idempotencyKey);

    Claim bindProviderIntent(CurrentAccessContext context, UUID rechargeId, String providerPaymentIntentId);

    BuyerWalletModels.RechargeView get(CurrentAccessContext context, UUID rechargeId);

    BuyerWalletRechargeProviderEventProcessor.Outcome processVerifiedProviderEvent(
            UUID tenantId, UUID workspaceId, BuyerWalletRechargeProviderEventProcessor.VerifiedEvent event);

    record Claim(UUID rechargeId, long amountMinor, String currency, BuyerWalletModels.RechargeStatus status,
                 String providerPaymentIntentId, java.time.Instant createdAt) { }
}
