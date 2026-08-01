package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import com.nexa.api.iam.application.port.out.WorkspacePreviewQueryPort;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Optional;

@Repository
@Profile("!test")
public class JpaWorkspacePreviewQueryAdapter implements WorkspacePreviewQueryPort {
	private final WorkspaceJpaRepository workspaces;
	public JpaWorkspacePreviewQueryAdapter(WorkspaceJpaRepository workspaces) { this.workspaces = workspaces; }
	@Override public Optional<PreviewRecord> findActiveBySlug(String slug) {
		return workspaces.findFirstBySlug(slug).map(value -> new PreviewRecord(value.getSlug(), value.getName(), value.getStatus()));
	}
}
