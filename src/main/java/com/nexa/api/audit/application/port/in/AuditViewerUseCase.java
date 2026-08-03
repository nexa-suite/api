package com.nexa.api.audit.application.port.in;

import com.nexa.api.audit.application.model.AuditModels.AuditEventView;
import com.nexa.api.audit.application.model.AuditModels.AuditPage;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface AuditViewerUseCase {
	AuditPage list(CurrentAccessContext context, int limit);
	AuditEventView detail(CurrentAccessContext context, String id);
}
