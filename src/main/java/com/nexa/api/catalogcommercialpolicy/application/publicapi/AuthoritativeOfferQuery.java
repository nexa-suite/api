package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.EffectivePricePolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Data-only BC-03 offer query shared by Buyer catalog and Sales snapshots. */
public interface AuthoritativeOfferQuery {
    Map<UUID, Offer> resolve(UUID tenantId, UUID workspaceId, List<UUID> skuIds,
                             UUID customerAccountId, String clientSegment, String buyerTier,
                             Map<UUID, BigDecimal> quantities, Instant effectiveAt);

    record Offer(BigDecimal basePrice, BigDecimal effectivePrice, BigDecimal discountAmount,
                 String currency, List<EffectivePricePolicy.AppliedPromotion> appliedPromotions,
                 Instant effectiveAt) {
        public Offer {
            appliedPromotions = List.copyOf(appliedPromotions);
        }
    }
}
