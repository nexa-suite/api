package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.WorkspacePreview;

public interface WorkspacePreviewUseCase {
	WorkspacePreview preview(String workspaceSlug, String clientFingerprint);
}
