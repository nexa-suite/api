package com.nexa.api.tenantaccessgovernance.support.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.port.out.SupportRequestPersistencePort;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSupportRequestAdapter implements SupportRequestPersistencePort {
	private static final String COLUMNS = "id,tenant_id,workspace_id,resource_type,resource_id,"
		+ "requested_by_operator_id,approved_by_operator_id,status,expires_at,created_at,version";
	private final JdbcTemplate jdbc;

	public JdbcSupportRequestAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public SupportRequestView create(UUID id, UUID tenantId, UUID workspaceId, UUID salesOrderId,
			UUID requesterOperatorId, Instant expiresAt, Instant now) {
		bindOperator(requesterOperatorId);
		List<SupportRequestView> inserted = jdbc.query("""
			INSERT INTO iam.internal_support_request
				(id,tenant_id,workspace_id,resource_type,resource_id,requested_by_operator_id,status,expires_at,created_at,updated_at,version)
			VALUES (?,?,?,'SALES_ORDER',?,?,'AWAITING_OWNER_CONSENT',?,?,?,0)
			RETURNING %s
			""".formatted(COLUMNS), rowMapper(now), id, tenantId, workspaceId, salesOrderId,
			requesterOperatorId, expiresAt, now, now);
		SupportRequestView created = only(inserted);
		appendAudit(created, "REQUESTED", "INTERNAL_OPERATOR", requesterOperatorId, null, now);
		return created;
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupportRequestView> listInternal(UUID operatorId, Instant now, int limit) {
		bindOperator(operatorId);
		return jdbc.query("SELECT " + COLUMNS + " FROM iam.internal_support_request ORDER BY created_at DESC,id LIMIT ?",
			rowMapper(now), limit);
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupportRequestView> listOwner(UUID tenantId, UUID workspaceId, Instant now, int limit) {
		bindOwnerScope(tenantId, workspaceId);
		return jdbc.query("SELECT " + COLUMNS + " FROM iam.internal_support_request "
			+ "WHERE tenant_id=? AND workspace_id=? AND status IN "
			+ "('AWAITING_OWNER_CONSENT','AWAITING_INDEPENDENT_APPROVAL','APPROVED') "
			+ "ORDER BY created_at DESC,id LIMIT ?", rowMapper(now), tenantId, workspaceId, limit);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SupportRequestView> findForOperator(UUID requestId, UUID operatorId, Instant now) {
		bindOperator(operatorId);
		return jdbc.query("SELECT " + COLUMNS + " FROM iam.internal_support_request "
			+ "WHERE id=? AND requested_by_operator_id=?", rowMapper(now), requestId, operatorId).stream().findFirst();
	}

	@Override
	@Transactional
	public Optional<SupportRequestView> consent(UUID requestId, UUID tenantId, UUID workspaceId,
			UUID ownerUserId, UUID ownerMembershipId, Instant now) {
		bindOwnerScope(tenantId, workspaceId);
		List<SupportRequestView> updated = jdbc.query("""
			UPDATE iam.internal_support_request
			SET status='AWAITING_INDEPENDENT_APPROVAL', owner_consent_user_id=?, owner_consent_membership_id=?,
				owner_consented_at=?, updated_at=?, version=version+1
			WHERE id=? AND tenant_id=? AND workspace_id=? AND status='AWAITING_OWNER_CONSENT' AND expires_at>?
			RETURNING %s
			""".formatted(COLUMNS), rowMapper(now), ownerUserId, ownerMembershipId, now, now,
			requestId, tenantId, workspaceId, now);
		if (updated.isEmpty()) return Optional.empty();
		SupportRequestView request = updated.getFirst();
		appendAudit(request, "OWNER_CONSENTED", "COMPANY_OWNER", ownerUserId, ownerMembershipId, now);
		return Optional.of(request);
	}

	@Override
	@Transactional
	public Optional<SupportRequestView> approve(UUID requestId, UUID approverOperatorId, Instant now) {
		bindOperator(approverOperatorId);
		List<SupportRequestView> updated = jdbc.query("""
			UPDATE iam.internal_support_request
			SET status='APPROVED', approved_by_operator_id=?, updated_at=?, version=version+1
			WHERE id=? AND status='AWAITING_INDEPENDENT_APPROVAL' AND owner_consented_at IS NOT NULL
				AND expires_at>? AND requested_by_operator_id<>?
			RETURNING %s
			""".formatted(COLUMNS), rowMapper(now), approverOperatorId, now, requestId, now, approverOperatorId);
		if (updated.isEmpty()) return Optional.empty();
		SupportRequestView request = updated.getFirst();
		appendAudit(request, "APPROVED", "INTERNAL_OPERATOR", approverOperatorId, null, now);
		return Optional.of(request);
	}

	@Override
	@Transactional
	public Optional<SupportRequestView> revokeByOperator(UUID requestId, UUID operatorId, Instant now) {
		bindOperator(operatorId);
		List<SupportRequestView> updated = jdbc.query("""
			UPDATE iam.internal_support_request
			SET status='REVOKED', revoked_at=?, revoked_by_operator_id=?, updated_at=?, version=version+1
			WHERE id=? AND status IN ('AWAITING_OWNER_CONSENT','AWAITING_INDEPENDENT_APPROVAL','APPROVED')
				AND expires_at>? AND (requested_by_operator_id=? OR approved_by_operator_id=?)
			RETURNING %s
			""".formatted(COLUMNS), rowMapper(now), now, operatorId, now, requestId, now, operatorId, operatorId);
		if (updated.isEmpty()) return Optional.empty();
		SupportRequestView request = updated.getFirst();
		appendAudit(request, "REVOKED", "INTERNAL_OPERATOR", operatorId, null, now);
		return Optional.of(request);
	}

	@Override
	@Transactional
	public Optional<SupportRequestView> revokeByOwner(UUID requestId, UUID tenantId, UUID workspaceId,
			UUID ownerUserId, UUID ownerMembershipId, Instant now) {
		bindOwnerScope(tenantId, workspaceId);
		List<SupportRequestView> updated = jdbc.query("""
			UPDATE iam.internal_support_request
			SET status='REVOKED', revoked_at=?, revoked_by_owner_user_id=?, revoked_by_owner_membership_id=?,
				updated_at=?, version=version+1
			WHERE id=? AND tenant_id=? AND workspace_id=?
				AND status IN ('AWAITING_OWNER_CONSENT','AWAITING_INDEPENDENT_APPROVAL','APPROVED') AND expires_at>?
			RETURNING %s
			""".formatted(COLUMNS), rowMapper(now), now, ownerUserId, ownerMembershipId, now,
			requestId, tenantId, workspaceId, now);
		if (updated.isEmpty()) return Optional.empty();
		SupportRequestView request = updated.getFirst();
		appendAudit(request, "REVOKED", "COMPANY_OWNER", ownerUserId, ownerMembershipId, now);
		return Optional.of(request);
	}

	@Override
	@Transactional
	public void appendOrderRead(SupportRequestView request, UUID requesterOperatorId, Instant now) {
		bindOperator(requesterOperatorId);
		appendAudit(request, "ORDER_READ", "INTERNAL_OPERATOR", requesterOperatorId, null, now);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(SupportOrderReadGrant grant, Instant now) {
		bindOperator(grant.requesterOperatorId());
		Boolean active = jdbc.queryForObject("""
			SELECT EXISTS (
				SELECT 1 FROM iam.internal_support_request
				WHERE id=? AND requested_by_operator_id=? AND tenant_id=? AND workspace_id=?
					AND resource_type='SALES_ORDER' AND resource_id=? AND expires_at=?
					AND status='APPROVED' AND owner_consented_at IS NOT NULL
					AND approved_by_operator_id IS NOT NULL AND approved_by_operator_id<>requested_by_operator_id
					AND expires_at>?
			)
			""", Boolean.class, grant.grantId(), grant.requesterOperatorId(), grant.tenantId(), grant.workspaceId(),
			grant.salesOrderId(), grant.expiresAt(), now);
		return Boolean.TRUE.equals(active);
	}

	private void appendAudit(SupportRequestView request, String action, String actorType, UUID actorId,
			UUID actorMembershipId, Instant now) {
		jdbc.update("""
			INSERT INTO iam.internal_support_audit_event
				(id,support_request_id,action,actor_type,actor_id,actor_membership_id,tenant_id,workspace_id,resource_type,resource_id,expires_at,occurred_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
			""", UUID.randomUUID(), request.id(), action, actorType, actorId, actorMembershipId,
			request.tenantId(), request.workspaceId(), request.resourceType(), request.resourceId(), request.expiresAt(), now);
	}

	private void bindOperator(UUID operatorId) {
		jdbc.queryForObject("SELECT set_config('app.current_internal_operator_id', ?, true)", String.class, operatorId.toString());
	}

	private void bindOwnerScope(UUID tenantId, UUID workspaceId) {
		jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true) || "
				+ "set_config('app.current_workspace_id', ?, true)", String.class,
			tenantId.toString(), workspaceId.toString());
	}

	private static SupportRequestView only(List<SupportRequestView> rows) {
		if (rows.size() != 1) throw new IllegalStateException("Support request insert did not return one row.");
		return rows.getFirst();
	}

	private static RowMapper<SupportRequestView> rowMapper(Instant now) {
		return (rs, row) -> map(rs, now);
	}

	private static SupportRequestView map(ResultSet rs, Instant now) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		UUID tenantId = rs.getObject("tenant_id", UUID.class);
		UUID workspaceId = rs.getObject("workspace_id", UUID.class);
		UUID resourceId = rs.getObject("resource_id", UUID.class);
		UUID requesterId = rs.getObject("requested_by_operator_id", UUID.class);
		UUID approverId = rs.getObject("approved_by_operator_id", UUID.class);
		Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
		String status = rs.getString("status");
		if (!now.isBefore(expiresAt) && !"REVOKED".equals(status)) status = "EXPIRED";
		return new SupportRequestView(id, tenantId, workspaceId, rs.getString("resource_type"), resourceId,
			requesterId, approverId, status, expiresAt, rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
	}
}
