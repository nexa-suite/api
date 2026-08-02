package com.nexa.api.iam.infrastructure.persistence;

import com.nexa.api.iam.application.port.out.WorkspacePreviewQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcWorkspacePreviewQueryAdapter implements WorkspacePreviewQueryPort {
	private final JdbcTemplate jdbc;

	public JdbcWorkspacePreviewQueryAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<PreviewRecord> findActiveBySlug(String slug) {
		return jdbc.query("select slug, name, status from tenant_management.workspace where lower(slug) = ? limit 1",
				(rs, row) -> new PreviewRecord(rs.getString("slug"), rs.getString("name"), rs.getString("status")),
				slug.toLowerCase(java.util.Locale.ROOT)).stream().findFirst()
				.filter(value -> "ACTIVE".equals(value.status()));
	}
}
