package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record SelectAccessContextCommand(String ticket, String accessToken, ClientSurface surface, String membershipId) {
	public SelectAccessContextCommand {
		boolean hasTicket = ticket != null && !ticket.isBlank();
		boolean hasAccessToken = accessToken != null && !accessToken.isBlank();
		if (hasTicket == hasAccessToken) throw new IllegalArgumentException("Exactly one access context authority is required");
		Objects.requireNonNull(surface, "Client surface is required");
		if (membershipId == null || membershipId.isBlank()) throw new IllegalArgumentException("Membership id is required");
	}

	public SelectAccessContextCommand(String ticket, ClientSurface surface, String membershipId) {
		this(ticket, null, surface, membershipId);
	}

	@Override
	public String toString() {
		return "SelectAccessContextCommand[authority=[REDACTED], surface=" + surface + ", membershipId=" + membershipId + "]";
	}
}
