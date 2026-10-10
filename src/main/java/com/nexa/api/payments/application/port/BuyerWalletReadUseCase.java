package com.nexa.api.payments.application.port;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Current-Buyer-only wallet query. No Buyer or Tenant selector is accepted. */
public interface BuyerWalletReadUseCase {
    BuyerWalletModels.WalletView getCurrentBuyerWallet(CurrentAccessContext context, int page, int size);
}
