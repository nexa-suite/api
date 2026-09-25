package com.nexa.api.salescommitment.domain.model.purchaserequest;

public enum PurchaseRequestStatus {
	DRAFT, SUBMITTED, CHANGES_PROPOSED, CONVERTED, REJECTED, WITHDRAWN, EXPIRED,
	/** Historical persisted states retained for reads and upgrade compatibility. New commands never write them. */
	@Deprecated IN_REVIEW, @Deprecated NEEDS_ADJUSTMENT, @Deprecated APPROVED,
	@Deprecated CANCELLED, @Deprecated CONVERTED_TO_ORDER;

	public boolean isTerminal() {
		return this == CONVERTED || this == REJECTED || this == WITHDRAWN || this == EXPIRED
				|| this == CANCELLED || this == CONVERTED_TO_ORDER;
	}

	/** Compatibility projection that removes obsolete review/approval/converted-to-order labels from new reads. */
	public PurchaseRequestStatus currentApiValue() {
		return switch (this) {
			case IN_REVIEW, NEEDS_ADJUSTMENT, APPROVED -> SUBMITTED;
			case CONVERTED_TO_ORDER -> CONVERTED;
			default -> this;
		};
	}
}
