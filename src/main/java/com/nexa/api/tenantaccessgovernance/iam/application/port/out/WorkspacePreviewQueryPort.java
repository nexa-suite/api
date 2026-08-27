package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import java.util.Optional;

public interface WorkspacePreviewQueryPort {
	Optional<PreviewRecord> findActiveBySlug(String slug);

	record PreviewRecord(String slug, String displayName, String status) { }
}
