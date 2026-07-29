package com.nexa.api.tenantmanagement.domain.model.identity;

import java.util.UUID;

public record WorkspaceId(UUID value) {
	public WorkspaceId {
		value = UuidIdentitySupport.required(value, "Workspace id");
	}

	public WorkspaceId(String value) {
		this(UuidIdentitySupport.parse(value, "Workspace id"));
	}

	public static WorkspaceId random() {
		return new WorkspaceId(UUID.randomUUID());
	}

	public static WorkspaceId from(String value) {
		return new WorkspaceId(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
