package com.nexa.api.payments.application.model;

import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only Buyer projection of stored funds and their signed ledger movements. */
public final class BuyerWalletModels {
    private BuyerWalletModels() { }

    public enum WalletStatus { ACTIVE, NOT_INITIALIZED }

    public record WalletView(WalletStatus status, String currency, BigDecimal postedBalance,
                             BigDecimal reservedBalance, BigDecimal availableBalance,
                             Page<MovementView> movements, Capabilities capabilities) {
        public WalletView(WalletStatus status, String currency, BigDecimal postedBalance,
                          BigDecimal reservedBalance, BigDecimal availableBalance,
                          Page<MovementView> movements) {
            this(status, currency, postedBalance, reservedBalance, availableBalance, movements,
                    new Capabilities(false));
        }
    }

    /** Server support only; this does not promise account funding or approve a purchase. */
    public record Capabilities(boolean orderPaymentSupported) { }

    public enum RechargeStatus { PREPARING, AWAITING_PAYMENT, SUCCEEDED, FAILED, CANCELLED, REJECTED }

    /** One-time checkout response. Client secret exists only in this response and is never persisted. */
    public record RechargeIntentView(UUID id, RechargeStatus status, BigDecimal amount, String currency,
                                     String provider, String providerPaymentIntentId, String clientSecret,
                                     Instant createdAt) { }

    /** Self-only durable recharge state; never contains provider credentials or client secrets. */
    public record RechargeView(UUID id, RechargeStatus status, BigDecimal amount, String currency,
                               String provider, String providerPaymentIntentId, Instant createdAt,
                               Instant updatedAt, Instant completedAt) { }

    public record MovementView(String type, BigDecimal amountDelta, Instant occurredAt) { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }

    @NamedInterface("payments-public")
    public record Snapshot(WalletStatus status, BigDecimal postedBalance, BigDecimal reservedBalance,
                           List<MovementView> movements, long totalMovements) {
        public Snapshot { movements = List.copyOf(movements); }
    }
}
