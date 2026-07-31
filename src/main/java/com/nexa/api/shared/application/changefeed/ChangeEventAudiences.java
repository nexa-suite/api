package com.nexa.api.shared.application.changefeed;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical mapping from change event types to authorized audiences. Every emitted
 * event type must be mapped explicitly; unmapped types fail fast so a new event can
 * never silently leak to every role.
 */
public final class ChangeEventAudiences {
	private ChangeEventAudiences() {
	}

	private static final Map<String, Set<ChangeEventAudience>> INTERNAL = Map.ofEntries(
			Map.entry("organization.membership.role-changed", EnumSet.of(ChangeEventAudience.OWNER)),
			Map.entry("organization.membership.suspended", EnumSet.of(ChangeEventAudience.OWNER)),
			Map.entry("organization.membership.reactivated", EnumSet.of(ChangeEventAudience.OWNER)),
			Map.entry("organization.workspace.updated", EnumSet.of(ChangeEventAudience.OWNER)),
			Map.entry("sales.client-account.created", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.client-account.updated", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.client-account.suspended", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.client-account.activated", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.client-account.buyer-associated", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.created", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.updated", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.submitted", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.review-started", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.adjustment-requested", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.approved", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.rejected", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.cancelled", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.purchase-request.converted", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.sales-order.created", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.sales-order.rejected", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.sales-order.cancelled", EnumSet.of(ChangeEventAudience.SALES)),
			Map.entry("sales.sales-order.confirmed", EnumSet.of(ChangeEventAudience.SALES, ChangeEventAudience.BUYER,
					ChangeEventAudience.WAREHOUSE, ChangeEventAudience.LOGISTICS)));

	public static Set<ChangeEventAudience> forEvent(String eventType, boolean explicitBuyerVisibility) {
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("Event type is required");
		}
		Set<ChangeEventAudience> audiences = INTERNAL.get(eventType);
		if (audiences == null) throw new IllegalStateException("No audience mapping for event type " + eventType);
		if (!explicitBuyerVisibility) return audiences;
		EnumSet<ChangeEventAudience> result = EnumSet.copyOf(audiences);
		if (audiences.contains(ChangeEventAudience.SALES)) result.add(ChangeEventAudience.BUYER);
		return Set.copyOf(result);
	}
}
