package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/**
 * Trusted runtime composition port for a current Buyer's own Tenant-local wallet.
 * Implementations must bind the BC-02 relationship check and this BC-08 query to
 * the same Tenant JDBC session; this is not a client-selected query.
 */
public interface BuyerWalletReadPort {
    BuyerWalletModels.Snapshot read(CurrentAccessContext context, UUID humanIdentityId, int page, int size);

    /** Tenant-bound server capability, independent of current balance and order approval. */
    default boolean orderPaymentSupported(CurrentAccessContext context) { return false; }
}
