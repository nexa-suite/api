package com.nexa.api.businessdocuments.domain;

/** Candidate payment lifecycle vocabulary pending payment-provider decisions. */
public enum PaymentStatus {
	PENDING,
	AUTHORIZED,
	SETTLED,
	FAILED,
	REFUNDED
}
