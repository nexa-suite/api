package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.model.WorkspacePreview;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.WorkspacePreviewUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.WorkspacePreviewQueryPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.WorkspacePreviewThrottlePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;

@Service
@Profile("!test")
public final class WorkspacePreviewService implements WorkspacePreviewUseCase {
	private static final String ALLOWED = "^[a-z0-9](?:[a-z0-9-]{1,78}[a-z0-9])?$";
	private final WorkspacePreviewQueryPort query;
	private final Clock clock;
	private final WorkspacePreviewThrottlePort throttle;

	public WorkspacePreviewService(WorkspacePreviewQueryPort query, Clock clock) { this(query, clock, (workspaceSlug, clientKey, now) -> true); }

	@Autowired
	public WorkspacePreviewService(WorkspacePreviewQueryPort query, Clock clock, WorkspacePreviewThrottlePort throttle) { this.query = query; this.clock = clock; this.throttle = throttle; }

	@Override
	public WorkspacePreview preview(String workspaceSlug, String clientFingerprint) {
		String slug = workspaceSlug == null ? "" : workspaceSlug.strip().toLowerCase(java.util.Locale.ROOT);
		if (slug.length() < 3 || slug.length() > 80 || !slug.matches(ALLOWED)) return WorkspacePreview.unknown();
		Instant now = clock.instant();
		if (!throttle.allow(slug, clientFingerprint, now)) return WorkspacePreview.unknown();
		return query.findActiveBySlug(slug).filter(value -> "ACTIVE".equalsIgnoreCase(value.status()))
				.map(value -> new WorkspacePreview(true, value.displayName(), slug + ".nexa.com.pe", null, true))
				.orElseGet(WorkspacePreview::unknown);
	}
}
