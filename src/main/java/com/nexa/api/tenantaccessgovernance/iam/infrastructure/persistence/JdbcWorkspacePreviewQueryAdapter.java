package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.WorkspacePreviewQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Locale;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcWorkspacePreviewQueryAdapter implements WorkspacePreviewQueryPort {
	private final JdbcTemplate jdbc;

	public JdbcWorkspacePreviewQueryAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PreviewRecord> findActiveBySlug(String slug) {
		String lookupSlug = slug.strip().toLowerCase(Locale.ROOT);
		jdbc.queryForObject("select set_config('app.workspace_lookup_slug', ?, true)", String.class, lookupSlug);
		try {
			var matches = jdbc.query("select slug, name, status from tenant_management.workspace where lower(slug) = ?",
					(rs, row) -> new PreviewRecord(rs.getString("slug"), rs.getString("name"), rs.getString("status")),
					lookupSlug);
			return matches.size() == 1 ? Optional.of(matches.get(0)).filter(value -> "ACTIVE".equals(value.status())) : Optional.empty();
		} finally {
			jdbc.queryForObject("select set_config('app.workspace_lookup_slug', '', true)", String.class);
		}
	}
}
