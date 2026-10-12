package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;

import java.time.Instant;
import java.util.Objects;

/** BC01's sole construction point for the in-process grant passed to BC04. */
public final class SupportOrderReadGrantIssuer {
	private SupportOrderReadGrantIssuer() { }

	public static SupportOrderReadGrant fromApprovedRequest(SupportRequestView request, Instant now) {
		Objects.requireNonNull(request, "Persisted support request is required.");
		Objects.requireNonNull(now, "Current time is required.");
		if (!"APPROVED".equals(request.status()) || !"SALES_ORDER".equals(request.resourceType())
				|| !now.isBefore(request.expiresAt()) || request.approvedByOperatorId() == null
				|| request.approvedByOperatorId().equals(request.requestedByOperatorId())) {
			throw new IllegalArgumentException("Only a current, independently approved sales-order request can issue a read grant.");
		}
		return new SupportOrderReadGrant(request.id(), request.requestedByOperatorId(), request.tenantId(),
			request.workspaceId(), request.resourceId(), request.expiresAt());
	}
}
