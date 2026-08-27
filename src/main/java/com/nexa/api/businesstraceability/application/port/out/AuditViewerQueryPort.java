package com.nexa.api.businesstraceability.application.port.out;

import com.nexa.api.businesstraceability.application.model.AuditModels.AuditEventRecord;

import java.util.List;
import java.util.Optional;

public interface AuditViewerQueryPort {
	List<AuditEventRecord> list(String tenantId, String workspaceId, int limit);
	Optional<AuditEventRecord> find(String tenantId, String workspaceId, String id);
}
