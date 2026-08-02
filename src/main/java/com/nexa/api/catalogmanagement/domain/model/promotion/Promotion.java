package com.nexa.api.catalogmanagement.domain.model.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Promotion {
    public enum DiscountType { PERCENTAGE, FIXED_AMOUNT }
    public enum StackingPolicy { EXCLUSIVE, STACKABLE }

    private final UUID id;
    private final DiscountType discountType;
    private final BigDecimal discountValue;
    private final Instant startsAt;
    private final Instant endsAt;
    private PromotionStatus status;

    private Promotion(UUID id, DiscountType discountType, BigDecimal discountValue, Instant startsAt, Instant endsAt) {
        this.id = Objects.requireNonNull(id, "Promotion id is required");
        this.discountType = Objects.requireNonNull(discountType, "Discount type is required");
        this.discountValue = Objects.requireNonNull(discountValue, "Discount value is required");
        if (discountValue.signum() < 0 || discountType == DiscountType.PERCENTAGE && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Promotion discount is invalid");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Promotion period is invalid");
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = PromotionStatus.DRAFT;
    }

    public static Promotion create(UUID id, DiscountType type, BigDecimal value, Instant startsAt, Instant endsAt) {
        return new Promotion(id, type, value, startsAt, endsAt);
    }
    public UUID id() { return id; }
    public DiscountType discountType() { return discountType; }
    public BigDecimal discountValue() { return discountValue; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public PromotionStatus status() { return status; }
    public void schedule() { require(PromotionStatus.DRAFT); status = PromotionStatus.SCHEDULED; }
    public void activate() { if (status != PromotionStatus.DRAFT && status != PromotionStatus.SCHEDULED && status != PromotionStatus.PAUSED) throw new IllegalStateException("Promotion cannot be activated"); status = PromotionStatus.ACTIVE; }
    public void pause() { require(PromotionStatus.ACTIVE); status = PromotionStatus.PAUSED; }
    public void cancel() { if (status == PromotionStatus.EXPIRED || status == PromotionStatus.CANCELLED) throw new IllegalStateException("Promotion is closed"); status = PromotionStatus.CANCELLED; }

    private void require(PromotionStatus expected) {
        if (status != expected) throw new IllegalStateException("Promotion transition is invalid");
    }
}
