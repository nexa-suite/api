package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.Objects;

public final class PurchaseRequestLine {
	private final PurchaseRequestLineId id;
	private final CatalogItemSnapshot catalogItem;
	private final RequestedQuantity quantity;
	private final String unit;
	private final String notes;

	public PurchaseRequestLine(PurchaseRequestLineId id, CatalogItemSnapshot catalogItem, RequestedQuantity quantity, String unit, String notes) {
		this.id = Objects.requireNonNull(id);
		this.catalogItem = Objects.requireNonNull(catalogItem);
		this.quantity = Objects.requireNonNull(quantity);
		if (unit == null || unit.isBlank() || unit.trim().length() > 32) throw new SalesInvariantViolation("Unit is invalid");
		this.unit = unit.trim();
		if (notes != null && notes.length() > 2000) throw new SalesInvariantViolation("Line notes are too long");
		this.notes = notes == null ? null : notes.trim();
	}
	public PurchaseRequestLineId id() { return id; }
	public CatalogItemSnapshot catalogItem() { return catalogItem; }
	public RequestedQuantity quantity() { return quantity; }
	public String unit() { return unit; }
	public String notes() { return notes; }
}
