package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record AccessContextTicketQuery(String ticket, String accessToken, ClientSurface surface) {
	public AccessContextTicketQuery {
		boolean hasTicket = ticket != null && !ticket.isBlank();
		boolean hasAccessToken = accessToken != null && !accessToken.isBlank();
		if (hasTicket == hasAccessToken) throw new IllegalArgumentException("Exactly one access context authority is required");
		Objects.requireNonNull(surface, "Client surface is required");
	}

	public AccessContextTicketQuery(String ticket, ClientSurface surface) {
		this(ticket, null, surface);
	}

	@Override
	public String toString() {
		return "AccessContextTicketQuery[authority=[REDACTED], surface=" + surface + "]";
	}
}
