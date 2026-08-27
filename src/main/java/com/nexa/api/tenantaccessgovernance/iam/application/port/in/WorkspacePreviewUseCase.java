package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.WorkspacePreview;

public interface WorkspacePreviewUseCase {
	WorkspacePreview preview(String workspaceSlug, String clientFingerprint);
}
