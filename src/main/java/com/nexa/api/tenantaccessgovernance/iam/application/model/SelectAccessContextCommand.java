package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record SelectAccessContextCommand(String ticket, ClientSurface surface, String membershipId) {
	public SelectAccessContextCommand {
		if (ticket == null || ticket.isBlank()) throw new IllegalArgumentException("Access context ticket is required");
		Objects.requireNonNull(surface, "Client surface is required");
		if (membershipId == null || membershipId.isBlank()) throw new IllegalArgumentException("Membership id is required");
	}

	@Override
	public String toString() {
		return "SelectAccessContextCommand[ticket=[REDACTED], surface=" + surface + ", membershipId=" + membershipId + "]";
	}
}
