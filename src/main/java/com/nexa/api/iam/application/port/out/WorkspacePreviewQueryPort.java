package com.nexa.api.iam.application.port.out;

import java.util.Optional;

public interface WorkspacePreviewQueryPort {
	Optional<PreviewRecord> findActiveBySlug(String slug);

	record PreviewRecord(String slug, String displayName, String status) { }
}
