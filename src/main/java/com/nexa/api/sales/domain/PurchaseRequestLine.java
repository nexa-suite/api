package com.nexa.api.sales.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class PurchaseRequestLine {
	private final String id;
	private final CatalogItemSnapshot snapshot;
	private BigDecimal quantity;
	private final String unit;
	private final String notes;

	public PurchaseRequestLine(String id, CatalogItemSnapshot snapshot, BigDecimal quantity, String unit, String notes) {
		this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
		this.snapshot = Objects.requireNonNull(snapshot);
		this.quantity = validQuantity(quantity);
		this.unit = unit == null || unit.isBlank() ? "unit" : unit.trim();
		this.notes = notes == null ? "" : notes.trim();
	}
	public void changeQuantity(BigDecimal value) { quantity = validQuantity(value); }
	public String id() { return id; }
	public CatalogItemSnapshot snapshot() { return snapshot; }
	public BigDecimal quantity() { return quantity; }
	public String unit() { return unit; }
	public String notes() { return notes; }
	private static BigDecimal validQuantity(BigDecimal value) { if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Quantity must be greater than zero"); return value; }
}
