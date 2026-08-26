package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.WorkspacePreviewQueryPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePreviewServiceTests {
	@Test void returnsConstantPublicShapeForActiveWorkspace() {
		var query = (WorkspacePreviewQueryPort) slug -> Optional.of(new WorkspacePreviewQueryPort.PreviewRecord(slug, "ICISA Distribuciones", "ACTIVE"));
		var service = new WorkspacePreviewService(query, Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
		var preview = service.preview(" ICISA ", "fingerprint");
		assertThat(preview.recognized()).isTrue();
		assertThat(preview.displayName()).isEqualTo("ICISA Distribuciones");
		assertThat(preview.workspaceUrl()).isEqualTo("icisa.nexa.com.pe");
	}
	@Test void unknownAndInvalidWorkspaceDoNotRevealState() {
		var query = (WorkspacePreviewQueryPort) slug -> Optional.empty();
		var service = new WorkspacePreviewService(query, Clock.systemUTC());
		assertThat(service.preview("bad slug", "fingerprint").recognized()).isFalse();
		assertThat(service.preview("unknown", "fingerprint").displayName()).isNull();
	}
}
