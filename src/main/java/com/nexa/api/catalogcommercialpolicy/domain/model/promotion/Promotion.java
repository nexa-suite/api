package com.nexa.api.catalogcommercialpolicy.domain.model.promotion;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Promotion {
    public static final int MIN_PRIORITY = -1_000_000;
    public static final int MAX_PRIORITY = 1_000_000;
    public enum DiscountType { PERCENTAGE, FIXED_AMOUNT }
    public enum StackingPolicy { EXCLUSIVE, STACKABLE }

    private final UUID id;
    private final DiscountType discountType;
    private final BigDecimal discountValue;
    private final String currency;
    private final Instant startsAt;
    private final Instant endsAt;
    private final BigDecimal minimumQuantity;
    private final StackingPolicy stackingPolicy;
    private final int priority;
    private PromotionStatus status;

    private Promotion(UUID id, DiscountType discountType, BigDecimal discountValue, Instant startsAt, Instant endsAt) {
        this(id, discountType, discountValue, null, startsAt, endsAt, BigDecimal.ONE, StackingPolicy.EXCLUSIVE, 0, false);
    }

    private Promotion(UUID id, DiscountType discountType, BigDecimal discountValue, String currency,
            Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, StackingPolicy stackingPolicy) {
        this(id, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, 0);
    }

    private Promotion(UUID id, DiscountType discountType, BigDecimal discountValue, String currency,
            Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, StackingPolicy stackingPolicy,
            int priority) {
        this(id, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, priority, true);
    }

    private Promotion(UUID id, DiscountType discountType, BigDecimal discountValue, String currency,
            Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, StackingPolicy stackingPolicy,
            int priority, boolean validateCurrency) {
        this.id = Objects.requireNonNull(id, "Promotion id is required");
        this.discountType = Objects.requireNonNull(discountType, "Discount type is required");
		BigDecimal normalizedDiscount = normalizeDiscount(Objects.requireNonNull(discountValue, "Discount value is required"));
		if (normalizedDiscount.signum() < 0
				|| discountType == DiscountType.PERCENTAGE && normalizedDiscount.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new IllegalArgumentException("Promotion discount is invalid");
		}
		this.discountValue = normalizedDiscount;
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Promotion period is invalid");
        BigDecimal normalizedQuantity = minimumQuantity == null ? BigDecimal.ONE : minimumQuantity.stripTrailingZeros();
        if (normalizedQuantity.signum() <= 0) throw new IllegalArgumentException("Promotion quantity is invalid");
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) throw new IllegalArgumentException("Promotion priority is invalid");
        this.currency = validateCurrency ? normalizeCurrency(discountType, currency) : currency;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.minimumQuantity = normalizedQuantity;
        this.stackingPolicy = stackingPolicy == null ? StackingPolicy.EXCLUSIVE : stackingPolicy;
        this.priority = priority;
        this.status = PromotionStatus.DRAFT;
    }

    public static Promotion create(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt) {
        return new LegacyPromotion(id, type, value, startsAt, endsAt).promotion();
    }

    public static Promotion create(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt,
            String currency, BigDecimal minimumQuantity, StackingPolicy stackingPolicy) {
        return new Promotion(id, type, value, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
    }

    public static Promotion create(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt,
            String currency, BigDecimal minimumQuantity, StackingPolicy stackingPolicy, int priority) {
        return new Promotion(id, type, value, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, priority);
    }

    public static Promotion create(UUID id, DiscountType type, BigDecimal value, String currency,
            Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, StackingPolicy stackingPolicy) {
        return create(id, type, value, startsAt, endsAt, currency, minimumQuantity, stackingPolicy);
    }

    public static Promotion restore(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt, PromotionStatus status) {
        Promotion promotion = LegacyPromotion.restore(id, type, value, startsAt, endsAt).promotion();
        promotion.status = Objects.requireNonNull(status, "Promotion status is required");
        return promotion;
    }

    public static Promotion restore(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt,
            String currency, BigDecimal minimumQuantity, StackingPolicy stackingPolicy, PromotionStatus status) {
        Promotion promotion = new Promotion(id, type, value, currency, startsAt, endsAt, minimumQuantity, stackingPolicy);
        promotion.status = Objects.requireNonNull(status, "Promotion status is required");
        return promotion;
    }

    public static Promotion restore(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt,
            String currency, BigDecimal minimumQuantity, StackingPolicy stackingPolicy, int priority,
            PromotionStatus status) {
        Promotion promotion = new Promotion(id, type, value, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, priority);
        promotion.status = Objects.requireNonNull(status, "Promotion status is required");
        return promotion;
    }

    public UUID id() { return id; }
    public DiscountType discountType() { return discountType; }
    public BigDecimal discountValue() { return discountValue; }
    public String currency() { return currency; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public BigDecimal minimumQuantity() { return minimumQuantity; }
    public StackingPolicy stackingPolicy() { return stackingPolicy; }
    public int priority() { return priority; }
    public PromotionStatus status() { return status; }
    public void schedule() { require(PromotionStatus.DRAFT); status = PromotionStatus.SCHEDULED; }
    public void activate() { if (status != PromotionStatus.DRAFT && status != PromotionStatus.SCHEDULED && status != PromotionStatus.PAUSED) throw new IllegalStateException("Promotion cannot be activated"); status = PromotionStatus.ACTIVE; }
    public void pause() { require(PromotionStatus.ACTIVE); status = PromotionStatus.PAUSED; }
    public void expire() {
        if (status != PromotionStatus.SCHEDULED && status != PromotionStatus.ACTIVE && status != PromotionStatus.PAUSED) {
            throw new IllegalStateException("Promotion cannot expire");
        }
        status = PromotionStatus.EXPIRED;
    }
    public void cancel() { if (status == PromotionStatus.EXPIRED || status == PromotionStatus.CANCELLED) throw new IllegalStateException("Promotion is closed"); status = PromotionStatus.CANCELLED; }

    private void require(PromotionStatus expected) {
        if (status != expected) throw new IllegalStateException("Promotion transition is invalid");
    }

    private static String normalizeCurrency(DiscountType type, String value) {
        if (type == DiscountType.PERCENTAGE) {
            if (value != null && !value.isBlank()) throw new IllegalArgumentException("Percentage promotion cannot define currency");
            return null;
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Fixed promotion currency is required");
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        Money.from(BigDecimal.ZERO, normalized);
        return normalized;
    }

	private static BigDecimal normalizeDiscount(BigDecimal value) {
		try { return value.setScale(2, RoundingMode.UNNECESSARY).stripTrailingZeros(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("Promotion discount cannot have more than two decimals", exception); }
	}

    /** Keeps the original four-argument factory permissive for legacy fixed-price callers. */
    private record LegacyPromotion(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt) {
        private Promotion promotion() {
            return new Promotion(id, type, value, null, startsAt, endsAt, BigDecimal.ONE, StackingPolicy.EXCLUSIVE, 0, false);
        }

        private static LegacyPromotion restore(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt) {
            return new LegacyPromotion(id, type, value, startsAt, endsAt);
        }
    }
}
