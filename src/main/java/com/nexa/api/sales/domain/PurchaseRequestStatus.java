package com.nexa.api.sales.domain;

/**
 * Candidate purchase-request lifecycle vocabulary.
 *
 * <p>The current API has no sales workflow implementation, so transitions are
 * intentionally not encoded here.</p>
 */
public enum PurchaseRequestStatus {
	DRAFT,
	SUBMITTED,
	APPROVED,
	REJECTED,
	CANCELLED
}
