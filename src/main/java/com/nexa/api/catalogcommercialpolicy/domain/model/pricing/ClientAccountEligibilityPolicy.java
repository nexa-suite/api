package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure buyer eligibility rules for account-targeted and segment-targeted promotions.
 * Missing buyer identity never grants a targeted promotion.
 */
public final class ClientAccountEligibilityPolicy {
	public boolean isEligible(PromotionCandidate promotion, UUID clientAccountId, String clientSegment, String buyerTier) {
		if (promotion == null) return false;
		if (!promotion.clientAccountIds().isEmpty()
				&& (clientAccountId == null || !promotion.clientAccountIds().contains(clientAccountId))) return false;
		for (PromotionCandidate.PromotionRule rule : promotion.rules()) {
			switch (rule.type()) {
				case "CLIENT_ACCOUNT", "CLIENT_ACCOUNT_ID" -> {
					if (clientAccountId == null || !rule.value().equalsIgnoreCase(clientAccountId.toString())) return false;
				}
				case "CLIENT_SEGMENT" -> {
					if (!sameToken(rule.value(), clientSegment)) return false;
				}
				case "BUYER_TIER" -> {
					if (!sameToken(rule.value(), buyerTier)) return false;
				}
				default -> { }
			}
		}
		return true;
	}

	public boolean isEligible(UUID clientAccountId, String clientSegment, String buyerTier,
			List<UUID> targetedClientAccountIds, List<PromotionCandidate.PromotionRule> rules) {
		if (targetedClientAccountIds != null && !targetedClientAccountIds.isEmpty()
				&& (clientAccountId == null || !targetedClientAccountIds.contains(clientAccountId))) return false;
		if (rules == null) return true;
		for (PromotionCandidate.PromotionRule rule : rules) {
			switch (rule.type()) {
				case "CLIENT_ACCOUNT", "CLIENT_ACCOUNT_ID" -> {
					if (clientAccountId == null || !rule.value().equalsIgnoreCase(clientAccountId.toString())) return false;
				}
				case "CLIENT_SEGMENT" -> {
					if (!sameToken(rule.value(), clientSegment)) return false;
				}
				case "BUYER_TIER" -> {
					if (!sameToken(rule.value(), buyerTier)) return false;
				}
				default -> { }
			}
		}
		return true;
	}

	private static boolean sameToken(String expected, String actual) {
		return expected != null && !expected.isBlank() && actual != null
				&& expected.strip().toUpperCase(Locale.ROOT).equals(actual.strip().toUpperCase(Locale.ROOT));
	}

}
