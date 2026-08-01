package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.WorkspacePreview;
import com.nexa.api.iam.application.port.in.WorkspacePreviewUseCase;
import com.nexa.api.iam.application.port.out.WorkspacePreviewQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Profile("!test")
public final class WorkspacePreviewService implements WorkspacePreviewUseCase {
	private static final String ALLOWED = "^[a-z0-9](?:[a-z0-9-]{1,78}[a-z0-9])?$";
	private final WorkspacePreviewQueryPort query;
	private final Clock clock;
	private final Map<String, Window> attempts = new ConcurrentHashMap<>();

	public WorkspacePreviewService(WorkspacePreviewQueryPort query, Clock clock) { this.query = query; this.clock = clock; }

	@Override
	public WorkspacePreview preview(String workspaceSlug, String clientFingerprint) {
		String slug = workspaceSlug == null ? "" : workspaceSlug.strip().toLowerCase(java.util.Locale.ROOT);
		if (slug.length() < 3 || slug.length() > 80 || !slug.matches(ALLOWED)) return WorkspacePreview.unknown();
		String key = (clientFingerprint == null ? "unknown" : clientFingerprint) + ":workspace-preview";
		Instant now = clock.instant();
		attempts.compute(key, (ignored, current) -> current == null || Duration.between(current.startedAt(), now).toMinutes() >= 1
				? new Window(now, 1) : new Window(current.startedAt(), current.count() + 1));
		if (attempts.get(key).count() > 30) return WorkspacePreview.unknown();
		return query.findActiveBySlug(slug).filter(value -> "ACTIVE".equalsIgnoreCase(value.status()))
				.map(value -> new WorkspacePreview(true, value.displayName(), slug + ".nexa.com.pe", null, true))
				.orElseGet(WorkspacePreview::unknown);
	}

	private record Window(Instant startedAt, int count) { }
}
