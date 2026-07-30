package com.nexa.api.warehouse.domain;

/**
 * Candidate warehouse stock states. State transitions belong to a future
 * warehouse aggregate and are not implied by this vocabulary.
 */
public enum InventoryStatus {
	AVAILABLE,
	RESERVED,
	QUARANTINED,
	EXPIRED,
	DEPLETED
}
