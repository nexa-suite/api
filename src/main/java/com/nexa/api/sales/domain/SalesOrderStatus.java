package com.nexa.api.sales.domain;

/**
 * Candidate sales-order lifecycle vocabulary.
 *
 * <p>The current API has no sales workflow implementation, so transitions are
 * intentionally not encoded here.</p>
 */
public enum SalesOrderStatus {
	DRAFT,
	CONFIRMED,
	COMPLETED,
	CANCELLED
}
