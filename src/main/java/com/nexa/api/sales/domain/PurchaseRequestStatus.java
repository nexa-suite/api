package com.nexa.api.sales.domain;

/**
 * Candidate purchase-request lifecycle vocabulary.
 *
 * <p>The current API has no sales workflow implementation, so transitions are
 * intentionally not encoded here.</p>
 */
public enum PurchaseRequestStatus {
	DRAFT, SUBMITTED, IN_REVIEW, NEEDS_ADJUSTMENT, APPROVED, REJECTED, CANCELLED, CONVERTED_TO_ORDER;

	public boolean isTerminal() {
		return this == APPROVED || this == REJECTED || this == CANCELLED || this == CONVERTED_TO_ORDER;
	}
}
