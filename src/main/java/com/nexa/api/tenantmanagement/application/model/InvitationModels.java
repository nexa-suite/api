package com.nexa.api.tenantmanagement.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class InvitationModels {
	private InvitationModels() { }

	public record InvitationView(UUID id, String workspaceId, String email, String displayName,
			Set<String> roles, String status, Instant expiresAt, long version, Instant createdAt) {
		public InvitationView { roles = Set.copyOf(roles); }
	}

	public record InvitationList(List<InvitationView> items, int page, int pageSize, boolean hasNext) {
		public InvitationList { items = List.copyOf(items); }
	}

	public record InvitationAcceptanceResult(UUID invitationId, UUID userId, UUID workspaceId, Set<String> roles) {
		public InvitationAcceptanceResult { roles = Set.copyOf(roles); }
	}
}
