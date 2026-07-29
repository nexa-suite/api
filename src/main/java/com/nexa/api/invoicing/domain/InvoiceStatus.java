package com.nexa.api.invoicing.domain;

/** Candidate invoice lifecycle vocabulary pending fiscal and workflow decisions. */
public enum InvoiceStatus {
	DRAFT,
	ISSUED,
	PAID,
	OVERDUE,
	VOID
}
