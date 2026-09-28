package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;

import java.util.Objects;

public record AccessContextTicketQuery(String ticket, ClientSurface surface) {
	public AccessContextTicketQuery {
		if (ticket == null || ticket.isBlank()) throw new IllegalArgumentException("Access context ticket is required");
		Objects.requireNonNull(surface, "Client surface is required");
	}

	@Override
	public String toString() {
		return "AccessContextTicketQuery[ticket=[REDACTED], surface=" + surface + "]";
	}
}
