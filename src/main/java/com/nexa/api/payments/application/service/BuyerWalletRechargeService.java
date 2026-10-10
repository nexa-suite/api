package com.nexa.api.payments.application.service;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Authorizes only the current Buyer and validates PEN recharge amount before Tenant composition. */
@Service
public final class BuyerWalletRechargeService {
    private static final BigDecimal MAXIMUM = new BigDecimal("999999.99");
    private final BuyerWalletRechargePort recharges;

    public BuyerWalletRechargeService(BuyerWalletRechargePort recharges) {
        this.recharges = Objects.requireNonNull(recharges);
    }

    public BuyerWalletModels.RechargeIntentView create(CurrentAccessContext context, BigDecimal amount,
                                                        String idempotencyKey) {
        requireBuyer(context, PermissionKey.PAYMENT_CREATE);
        requireIdempotencyKey(idempotencyKey);
        BigDecimal normalized = requireAmount(amount);
        return recharges.create(context, normalized, idempotencyKey);
    }

    public BuyerWalletModels.RechargeView get(CurrentAccessContext context, UUID rechargeId) {
        requireBuyer(context, PermissionKey.PAYMENT_READ);
        Objects.requireNonNull(rechargeId, "Recharge id is required");
        return recharges.get(context, rechargeId);
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("Wallet recharge amount must be between 0.01 and 999999.99 PEN");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("Wallet recharge amount must have at most two decimal places");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
    }

    private static void requireBuyer(CurrentAccessContext context, PermissionKey permission) {
        if (context == null) throw new AccessPolicyViolation("Verified Buyer access context is required");
        context.requirePermission(permission);
        if (!context.hasRoleCode("BUYER")) {
            throw new AccessPolicyViolation("An active Buyer membership is required");
        }
    }
}
