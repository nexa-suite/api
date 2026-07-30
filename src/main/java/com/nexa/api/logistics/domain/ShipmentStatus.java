package com.nexa.api.logistics.domain;

/** Candidate shipment lifecycle vocabulary pending the logistics workflow decision. */
public enum ShipmentStatus {
	PLANNED,
	DISPATCHED,
	IN_TRANSIT,
	DELIVERED,
	FAILED,
	CANCELLED
}
