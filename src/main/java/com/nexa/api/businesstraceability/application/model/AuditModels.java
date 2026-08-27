package com.nexa.api.businesstraceability.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AuditModels {
	private AuditModels() { }

	public record AuditEventRecord(String id, String tenantId, String workspaceId, String actorMembershipId,
			String actorWorkArea, String eventType, String subjectType, String subjectId, String correlationId,
			Instant occurredAt, Map<String, Object> metadata) {
		public AuditEventRecord {
			metadata = metadata == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
		}
	}

	public record AuditEventView(String id, String tenantId, String workspaceId, String actorMembershipId,
			String actorWorkArea, String eventType, String subjectType, String subjectId, String correlationId,
			Instant occurredAt, Map<String, Object> metadata) {
		public AuditEventView {
			metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		}
	}

	public record AuditPage(List<AuditEventView> items, int limit) {
		public AuditPage { items = List.copyOf(items); }
	}
}
