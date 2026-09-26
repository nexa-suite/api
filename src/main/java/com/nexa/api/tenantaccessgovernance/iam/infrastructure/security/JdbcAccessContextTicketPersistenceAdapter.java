package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextTicketPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccessContextTicketPersistenceAdapter implements AccessContextTicketPersistencePort {
	private static final String SELECT_COLUMNS = "select ticket_hash, user_id, surface, issued_at, expires_at, consumed_at, revoked_at "
			+ "from iam.access_context_selection_ticket where ticket_hash = ?";
	private final JdbcTemplate jdbc;

	public JdbcAccessContextTicketPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void create(AccessContextTicketRecord ticket) {
		jdbc.update("insert into iam.access_context_selection_ticket "
				+ "(ticket_hash, user_id, surface, issued_at, expires_at, consumed_at) values (?, ?, ?, ?, ?, null)",
			ticket.ticketHash(), UUID.fromString(ticket.userAccountId().value()), ticket.surface().name(),
			Timestamp.from(ticket.issuedAt()), Timestamp.from(ticket.expiresAt()));
	}

	@Override
	public Optional<AccessContextTicketRecord> findByHash(String ticketHash) {
		return queryOne(SELECT_COLUMNS, ticketHash);
	}

	@Override
	public Optional<AccessContextTicketRecord> findByHashForUpdate(String ticketHash) {
		return queryOne(SELECT_COLUMNS + " for update", ticketHash);
	}

	@Override
	public boolean consume(String ticketHash, Instant consumedAt) {
		return jdbc.update("update iam.access_context_selection_ticket set consumed_at = ? "
				+ "where ticket_hash = ? and consumed_at is null and revoked_at is null and expires_at > ?",
			Timestamp.from(consumedAt), ticketHash, Timestamp.from(consumedAt)) == 1;
	}

	@Override
	public int invalidatePendingForUser(UserAccountId userAccountId, Instant revokedAt) {
		return jdbc.update("update iam.access_context_selection_ticket set revoked_at = ? "
				+ "where user_id = ? and consumed_at is null and revoked_at is null",
			Timestamp.from(revokedAt), UUID.fromString(userAccountId.value()));
	}

	private Optional<AccessContextTicketRecord> queryOne(String sql, String hash) {
		return jdbc.query(sql, (ResultSet resultSet) -> resultSet.next()
				? Optional.of(map(resultSet)) : Optional.empty(), hash);
	}

	private static AccessContextTicketRecord map(ResultSet resultSet) throws SQLException {
		Instant consumedAt = resultSet.getTimestamp("consumed_at") == null ? null : resultSet.getTimestamp("consumed_at").toInstant();
		Instant revokedAt = resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant();
		return new AccessContextTicketRecord(resultSet.getString("ticket_hash"),
				new UserAccountId(resultSet.getObject("user_id", UUID.class).toString()),
				ClientSurface.valueOf(resultSet.getString("surface")),
				resultSet.getTimestamp("issued_at").toInstant(), resultSet.getTimestamp("expires_at").toInstant(), consumedAt, revokedAt);
	}
}
