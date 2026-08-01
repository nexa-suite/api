package com.nexa.api.iam.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface SecurityAuditPort {
	record Event(String type, UUID actorUserId, UUID targetUserId, UUID tenantId, UUID workspaceId,
			String surface, String correlationId, String traceId, Instant occurredAt, Map<String, Object> metadata) {}

	void append(Event event);
}
