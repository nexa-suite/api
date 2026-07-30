package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.tenantmanagement.application.exception.InaccessibleTenantException;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantmanagement.domain.model.membership.VerifiedMembership;

import java.util.Objects;

public final class ResolveCurrentAccessContextService implements ResolveCurrentAccessContextUseCase {
	private final VerifiedMembershipResolutionPort resolutionPort;

	public ResolveCurrentAccessContextService(VerifiedMembershipResolutionPort resolutionPort) {
		this.resolutionPort = Objects.requireNonNull(resolutionPort, "Verified membership resolution port is required");
	}

	@Override
	public CurrentAccessContext resolve(CurrentAccessRequest request) {
		CurrentAccessRequest safeRequest = Objects.requireNonNull(request, "Current access request is required");
		VerifiedMembership verifiedMembership = resolutionPort
				.resolve(safeRequest.userId(), safeRequest.tenantId(), safeRequest.workspaceId())
				.orElseThrow(InaccessibleTenantException::new);

		if (!verifiedMembership.belongsTo(safeRequest.userId(), safeRequest.tenantId(), safeRequest.workspaceId())
				|| !verifiedMembership.isAccessible()) {
			throw new InaccessibleTenantException();
		}

		return CurrentAccessContext.from(verifiedMembership, safeRequest.surface());
	}
}
