package com.nexa.api.payments.application.service;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.port.BuyerWalletReadUseCase;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Authorizes and projects only the current Buyer's stored-funds wallet. */
@Service
public class BuyerWalletReadService implements BuyerWalletReadUseCase {
    private static final int MAX_PAGE_SIZE = 100;

    private final BuyerWalletReadPort wallets;

    public BuyerWalletReadService(BuyerWalletReadPort wallets) {
        this.wallets = wallets;
    }

    @Override
    public BuyerWalletModels.WalletView getCurrentBuyerWallet(CurrentAccessContext context, int page, int size) {
        if (context == null) throw new AccessPolicyViolation("Verified Buyer access context is required");
        context.requirePermission(PermissionKey.PAYMENT_READ);
        if (!context.hasRoleCode("BUYER")) {
            throw new AccessPolicyViolation("An active Buyer membership is required");
        }
        UUID humanIdentityId = context.userId().value();

        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        BuyerWalletModels.Snapshot snapshot = wallets.read(context, humanIdentityId, safePage, safeSize);
        BigDecimalBalance balance = balances(snapshot);
        return new BuyerWalletModels.WalletView(snapshot.status(), "PEN", balance.posted(), balance.reserved(),
                balance.available(), new BuyerWalletModels.Page<>(snapshot.movements(), safePage, safeSize,
                        snapshot.totalMovements()),
                new BuyerWalletModels.Capabilities(wallets.orderPaymentSupported(context)));
    }

    private static BigDecimalBalance balances(BuyerWalletModels.Snapshot snapshot) {
        if (snapshot.status() == BuyerWalletModels.WalletStatus.NOT_INITIALIZED) {
            return new BigDecimalBalance(null, null, null);
        }
        if (snapshot.postedBalance() == null || snapshot.reservedBalance() == null
                || snapshot.reservedBalance().compareTo(snapshot.postedBalance()) > 0) {
            throw new IllegalStateException("Buyer wallet balance projection is inconsistent");
        }
        return new BigDecimalBalance(snapshot.postedBalance(), snapshot.reservedBalance(),
                snapshot.postedBalance().subtract(snapshot.reservedBalance()));
    }

    private record BigDecimalBalance(java.math.BigDecimal posted, java.math.BigDecimal reserved,
                                     java.math.BigDecimal available) { }
}
