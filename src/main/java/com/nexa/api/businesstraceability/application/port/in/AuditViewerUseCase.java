package com.nexa.api.businesstraceability.application.port.in;

import com.nexa.api.businesstraceability.application.model.AuditModels.AuditEventView;
import com.nexa.api.businesstraceability.application.model.AuditModels.AuditPage;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

public interface AuditViewerUseCase {
	AuditPage list(CurrentAccessContext context, int limit);
	AuditEventView detail(CurrentAccessContext context, String id);
}
