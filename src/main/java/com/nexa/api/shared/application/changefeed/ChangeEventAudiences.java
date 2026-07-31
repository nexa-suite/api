package com.nexa.api.shared.application.changefeed;

import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical mapping from change event types to authorized audiences. Every emitted
 * event type must be mapped explicitly; unmapped types fail fast so a new event can
 * never silently leak to every role.
 */
public final class ChangeEventAudiences {
	private ChangeEventAudiences() {
	}

	public static Set<ChangeEventAudience> forEvent(String eventType, boolean clientAccountScoped) {
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("Event type is required");
		}
		if (eventType.startsWith("organization.membership.") || eventType.startsWith("organization.workspace.")) {
			return EnumSet.of(ChangeEventAudience.OWNER);
		}
		if (eventType.startsWith("sales.client-account.")) {
			return EnumSet.of(ChangeEventAudience.SALES);
		}
		if (eventType.startsWith("sales.purchase-request.")) {
			return scoped(EnumSet.of(ChangeEventAudience.SALES), clientAccountScoped);
		}
		if ("sales.sales-order.confirmed".equals(eventType)) {
			return scoped(EnumSet.of(ChangeEventAudience.SALES, ChangeEventAudience.WAREHOUSE,
					ChangeEventAudience.LOGISTICS), clientAccountScoped);
		}
		if (eventType.startsWith("sales.sales-order.")) {
			return scoped(EnumSet.of(ChangeEventAudience.SALES), clientAccountScoped);
		}
		if (eventType.startsWith("warehouse.reservation.")) {
			return EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.LOGISTICS, ChangeEventAudience.SALES);
		}
		if (eventType.startsWith("warehouse.inventory.") || eventType.startsWith("warehouse.lot.")
				|| eventType.startsWith("warehouse.stock.")) {
			return EnumSet.of(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.LOGISTICS);
		}
		if (eventType.startsWith("logistics.dispatch.")) {
			return scoped(EnumSet.of(ChangeEventAudience.LOGISTICS, ChangeEventAudience.WAREHOUSE,
					ChangeEventAudience.SALES), clientAccountScoped);
		}
		if (eventType.startsWith("invoicing.")) {
			return scoped(EnumSet.of(ChangeEventAudience.SALES), clientAccountScoped);
		}
		if (eventType.startsWith("documents.")) {
			return scoped(EnumSet.of(ChangeEventAudience.SALES, ChangeEventAudience.LOGISTICS), clientAccountScoped);
		}
		throw new IllegalArgumentException("No audience mapping for event type " + eventType);
	}

	private static Set<ChangeEventAudience> scoped(EnumSet<ChangeEventAudience> audiences, boolean clientAccountScoped) {
		if (clientAccountScoped) {
			audiences.add(ChangeEventAudience.BUYER);
		}
		return audiences;
	}
}
