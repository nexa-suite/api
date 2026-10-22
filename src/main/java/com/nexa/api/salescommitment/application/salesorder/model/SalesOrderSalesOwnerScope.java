package com.nexa.api.salescommitment.application.salesorder.model;

import java.util.Set;
import java.util.UUID;

/** Server-resolved active client accounts assigned to one Sales membership. */
public record SalesOrderSalesOwnerScope(Set<UUID> activeClientAccountIds) {
	public SalesOrderSalesOwnerScope {
		activeClientAccountIds = activeClientAccountIds == null ? Set.of() : Set.copyOf(activeClientAccountIds);
	}
}
