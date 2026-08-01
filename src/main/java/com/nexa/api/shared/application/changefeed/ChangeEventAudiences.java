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
					ChangeEventAudience.WAREHOUSE, ChangeEventAudience.LOGISTICS)),
			Map.entry("warehouse.warehouse.created", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.warehouse.updated", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.zone.created", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.zone.updated", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.received", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.adjusted", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.waste-recorded", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.blocked", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.quarantined", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.lot.restored", EnumSet.of(ChangeEventAudience.WAREHOUSE)),
			Map.entry("warehouse.reservation.created", EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES, ChangeEventAudience.LOGISTICS)),
			Map.entry("warehouse.reservation.shortage", EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES)),
			Map.entry("warehouse.reservation.released", EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES, ChangeEventAudience.LOGISTICS)),
			Map.entry("warehouse.reservation.expired", EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES, ChangeEventAudience.LOGISTICS)),
			Map.entry("warehouse.reservation.consumed", EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES, ChangeEventAudience.LOGISTICS)),
			Map.entry("logistics.dispatch.created", EnumSet.of(ChangeEventAudience.LOGISTICS)),
			Map.entry("logistics.dispatch.preparation-started", EnumSet.of(ChangeEventAudience.LOGISTICS)),
			Map.entry("logistics.dispatch.assigned", EnumSet.of(ChangeEventAudience.LOGISTICS)),
			Map.entry("logistics.dispatch.scheduled", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.ready", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.WAREHOUSE)),
			Map.entry("logistics.dispatch.route-started", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.WAREHOUSE, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.temperature-recorded", EnumSet.of(ChangeEventAudience.LOGISTICS)),
			Map.entry("logistics.dispatch.incident-recorded", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.reprogrammed", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.cancelled", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.delivered", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.pod.completed", EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES)),
			Map.entry("logistics.dispatch.buyer-temperature-review", EnumSet.of(ChangeEventAudience.BUYER)));

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
