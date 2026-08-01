package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.port.in.IamSecurityUseCase;
import com.nexa.api.iam.application.port.out.PasswordResetDeliveryPort;
import com.nexa.api.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.iam.domain.model.password.PasswordPolicy;
import com.nexa.api.shared.infrastructure.observability.SecurityMetrics;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!test")
public class JdbcIamSecurityAdapter implements IamSecurityUseCase {
	private static final String GENERIC_RESET_MESSAGE = "If the account can receive a reset, instructions will be delivered.";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final JdbcTemplate jdbc;
	private final SecurityAuditPort audit;
	private final PasswordResetDeliveryPort delivery;
	private final BCryptPasswordEncoder encoder;
	private final Clock clock;
	private final Duration resetTtl;
	private final String operatorToken;
	private final SecurityMetrics metrics;

	public JdbcIamSecurityAdapter(JdbcTemplate jdbc, SecurityAuditPort audit, PasswordResetDeliveryPort delivery,
			@org.springframework.beans.factory.annotation.Value("${nexa.security.bcrypt-strength:12}") int bcryptStrength,
			@org.springframework.beans.factory.annotation.Value("${nexa.security.reset.ttl:PT30M}") Duration resetTtl,
			@org.springframework.beans.factory.annotation.Value("${nexa.security.system-operator-token:}") String operatorToken,
			Clock clock, SecurityMetrics metrics) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.delivery = delivery;
		this.encoder = new BCryptPasswordEncoder(bcryptStrength);
		this.resetTtl = resetTtl;
		this.operatorToken = operatorToken == null ? "" : operatorToken;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Override
	@Transactional(readOnly = true)
	public Profile profile(Actor actor) {
		return jdbc.queryForObject("select id,email,display_name,phone,preferred_language,timezone,version from iam.user_account where id=?",
				(rs, row) -> new Profile(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
						rs.getString("phone"), rs.getString("preferred_language"), rs.getString("timezone"), rs.getLong("version")), actor.userId());
	}

	@Override
	@Transactional
	public Profile updateProfile(Actor actor, ProfilePatch patch) {
		validateProfile(patch);
		int updated = jdbc.update("update iam.user_account set display_name=?,phone=?,preferred_language=?,timezone=?,updated_at=now(),version=version+1 where id=? and version=?",
				patch.displayName().trim(), blankToNull(patch.phone()), patch.preferredLanguage(), patch.timezone(), actor.userId(), patch.version());
		if (updated != 1) throw new IamSecurityException("PROFILE_VERSION_CONFLICT");
		audit.append(event("PROFILE_UPDATED", actor, actor.userId(), Map.of("version", patch.version() + 1)));
		return profile(actor);
	}

	@Override
	@Transactional
	public void changePassword(Actor actor, String currentPassword, String newPassword) {
		if (!PasswordPolicy.isValid(newPassword)) throw new IamSecurityException("PASSWORD_POLICY_INVALID");
		String hash = jdbc.query("select password_hash from iam.password_credential where user_id=?", (rs, row) -> rs.getString(1), actor.userId())
				.stream().findFirst().orElseThrow(() -> new IamSecurityException("PASSWORD_CHANGE_FAILED"));
		if (!encoder.matches(currentPassword, hash)) throw new IamSecurityException("PASSWORD_CHANGE_FAILED");
		if (encoder.matches(newPassword, hash)) throw new IamSecurityException("PASSWORD_REUSE_NOT_ALLOWED");
		Instant now = clock.instant();
		jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=? where user_id=?", encoder.encode(newPassword), sql(now), actor.userId());
		jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where user_id=? and revoked_at is null and id<>?", sql(now), sql(now), actor.userId(), actor.sessionId());
		audit.append(event("PASSWORD_CHANGED", actor, actor.userId(), Map.of("otherSessionsRevoked", true)));
		metrics.increment("password.changed");
		String email = jdbc.queryForObject("select email from iam.user_account where id=?", String.class, actor.userId());
		delivery.sendPasswordChanged(email, actor.surface());
	}

	@Override
	@Transactional(readOnly = true)
	public List<Session> sessions(Actor actor) {
		return jdbc.query("select id,surface,created_at,coalesce(last_seen_at,last_used_at,created_at) as last_seen_at,expires_at,device_label,coarse_ip from iam.refresh_session where user_id=? and revoked_at is null and expires_at>now() order by created_at desc limit 50",
				(rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getString("surface"), rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), rs.getObject("id", UUID.class).equals(actor.sessionId()),
					rs.getString("device_label"), rs.getString("coarse_ip")), actor.userId());
	}

	@Override
	@Transactional
	public void revokeSession(Actor actor, UUID sessionId) {
		Instant now = clock.instant();
		int changed = jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where id=? and user_id=? and revoked_at is null",
				sql(now), sql(now), sessionId, actor.userId());
		if (changed != 1) throw new ApiResourceNotFoundException("session");
		audit.append(event("SESSION_REVOKED", actor, actor.userId(), Map.of("sessionId", sessionId.toString())));
		metrics.increment("session.revoked");
	}

	@Override
	@Transactional
	public void revokeOtherSessions(Actor actor) {
		Instant now = clock.instant();
		int changed = jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where user_id=? and id<>? and revoked_at is null",
				sql(now), sql(now), actor.userId(), actor.sessionId());
		audit.append(event("ALL_OTHER_SESSIONS_REVOKED", actor, actor.userId(), Map.of("count", changed)));
		metrics.increment("session.other_revoked");
	}

	@Override
	@Transactional
	public String requestPasswordReset(String email, String surface, String correlationId, String traceId) {
		String normalized = normalizeEmail(email);
		Instant now = clock.instant();
		long recent = jdbc.queryForObject("select count(*) from iam.password_reset_request where normalized_email=? and created_at>?", Long.class, normalized, sql(now.minus(Duration.ofMinutes(10))));
		if (recent >= 3) throw new IamSecurityException("RESET_RATE_LIMITED");
		var users = jdbc.query("select id,email from iam.user_account where normalized_email=? and status='ACTIVE'", (rs, row) -> new User(rs.getObject("id", UUID.class), rs.getString("email")), normalized);
		if (!users.isEmpty()) {
			String token = opaqueToken();
			Instant expires = now.plus(resetTtl);
			jdbc.update("update iam.password_reset_request set status='REVOKED' where normalized_email=? and status='PENDING'", normalized);
			jdbc.update("insert into iam.password_reset_request (id,normalized_email,surface,token_hash,status,attempts,expires_at,created_at) values (?,?,?,?, 'PENDING',0,?,?)",
					UUID.randomUUID(), normalized, surface, sha256(token), sql(expires), sql(now));
			delivery.sendReset(users.get(0).email(), surface, token, expires);
		}
		audit.append(new SecurityAuditPort.Event("PASSWORD_RESET_REQUESTED", null, users.isEmpty() ? null : users.get(0).id(), null, null, surface,
				valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("accountResponse", "generic")));
		metrics.increment("password_reset.requested");
		return GENERIC_RESET_MESSAGE;
	}

	@Override
	@Transactional
	public void resetPassword(String token, String newPassword, String correlationId, String traceId) {
		if (token == null || token.isBlank() || !PasswordPolicy.isValid(newPassword)) throw new IamSecurityException("RESET_INVALID");
		Instant now = clock.instant();
		var rows = jdbc.query("select id,normalized_email,surface,status,expires_at from iam.password_reset_request where token_hash=? for update",
				(rs, row) -> new Reset(rs.getObject("id", UUID.class), rs.getString("normalized_email"), rs.getString("surface"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()), sha256(token));
		if (rows.isEmpty() || !"PENDING".equals(rows.get(0).status()) || !rows.get(0).expiresAt().isAfter(now)) throw new IamSecurityException("RESET_INVALID");
		Reset reset = rows.get(0);
		var users = jdbc.query("select id,email from iam.user_account where normalized_email=? and status='ACTIVE' for update", (rs, row) -> new User(rs.getObject("id", UUID.class), rs.getString("email")), reset.email());
		if (users.isEmpty()) throw new IamSecurityException("RESET_INVALID");
		User user = users.get(0);
		int updated = jdbc.update("update iam.password_reset_request set status='CONSUMED',consumed_at=?,attempts=attempts+1 where id=? and status='PENDING'", sql(now), reset.id());
		if (updated != 1) throw new IamSecurityException("RESET_INVALID");
		jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=? where user_id=?", encoder.encode(newPassword), sql(now), user.id());
		jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where user_id=? and revoked_at is null", sql(now), sql(now), user.id());
		audit.append(new SecurityAuditPort.Event("PASSWORD_RESET_COMPLETED", null, user.id(), null, null, reset.surface(), valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("sessionsRevoked", true)));
		metrics.increment("password_reset.completed");
		delivery.sendPasswordChanged(user.email(), reset.surface());
	}

	@Override
	@Transactional
	public Registration submitRegistration(RegistrationRequest request) {
		validateRegistration(request);
		Instant now = clock.instant();
		String slug = request.workspaceSlug().trim().toLowerCase(Locale.ROOT);
		if (!jdbc.query("select id from tenant_management.organization_registration where workspace_slug=?", (rs, row) -> rs.getObject(1), slug).isEmpty())
			throw new IamSecurityException("REGISTRATION_SLUG_CONFLICT");
		UUID id = UUID.randomUUID();
		jdbc.update("insert into tenant_management.organization_registration (id,legal_name,display_name,normalized_legal_name,business_identifier,operation_category,storage_site_name,storage_site_address,founder_email,founder_display_name,workspace_name,workspace_slug,reference_plan,terms_version,terms_accepted_at,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING_ACTIVATION',?,?,0)",
				id, request.legalName().trim(), request.displayName().trim(), request.legalName().trim().toLowerCase(Locale.ROOT), blankToNull(request.businessIdentifier()), request.operationCategory(), request.storageSiteName(), request.storageSiteAddress(), normalizeEmail(request.founderEmail()), request.founderDisplayName(), request.workspaceName(), slug, request.referencePlan(), request.termsVersion(), sql(now), sql(now), sql(now));
		return new Registration(id.toString(), "PENDING_ACTIVATION", now);
	}

	@Override
	@Transactional(readOnly = true)
	public Registration registration(UUID registrationId) {
		return jdbc.queryForObject("select id,status,created_at from tenant_management.organization_registration where id=?", (rs, row) -> new Registration(rs.getObject("id", UUID.class).toString(), rs.getString("status"), rs.getTimestamp("created_at").toInstant()), registrationId);
	}

	@Override
	@Transactional
	public Activation activate(UUID registrationId, String suppliedOperatorToken, String correlationId, String traceId) {
		requireOperator(suppliedOperatorToken);
		Instant now = clock.instant();
		var rows = jdbc.query("select * from tenant_management.organization_registration where id=? for update", (rs, row) -> new RegistrationRow(rs), registrationId);
		if (rows.isEmpty()) throw new ApiResourceNotFoundException("organization registration");
		RegistrationRow registration = rows.get(0);
		if (!"PENDING_ACTIVATION".equals(registration.status)) throw new IamSecurityException("REGISTRATION_NOT_PENDING");
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) values (?,?,?,'ACTIVE',?,?,0)", tenantId, registration.displayName, registration.workspaceSlug, sql(now), sql(now));
		jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) values (?,?,?,?,'ACTIVE',?,?,0)", workspaceId, tenantId, registration.workspaceName, registration.workspaceSlug, sql(now), sql(now));
		UUID userId = jdbc.query("select id from iam.user_account where normalized_email=?", (rs, row) -> rs.getObject(1, UUID.class), registration.founderEmail).stream().findFirst().orElse(null);
		if (userId == null) {
			userId = UUID.randomUUID();
			jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,'es','ACTIVE',?,?,0)", userId, registration.founderEmail, registration.founderEmail, registration.founderEmail, registration.founderEmail, registration.founderDisplayName, sql(now), sql(now));
			jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) values (?,?, 'bcrypt', ?)", userId, encoder.encode(opaqueToken()), sql(now));
		}
		String founderResetToken = opaqueToken();
		Instant founderResetExpiry = now.plus(resetTtl);
		jdbc.update("update iam.password_reset_request set status='REVOKED' where normalized_email=? and status='PENDING'", registration.founderEmail);
		jdbc.update("insert into iam.password_reset_request (id,normalized_email,surface,token_hash,status,attempts,expires_at,created_at) values (?,?,?,?, 'PENDING',0,?,?)",
				UUID.randomUUID(), registration.founderEmail, "PLATFORM", sha256(founderResetToken), sql(founderResetExpiry), sql(now));
		delivery.sendReset(registration.founderEmail, "PLATFORM", founderResetToken, founderResetExpiry);
		UUID membershipId = UUID.randomUUID();
		jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)", membershipId, workspaceId, userId, sql(now), sql(now));
		for (String role : List.of("TENANT_ADMIN", "COMPANY_OWNER")) jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) values (?,?,?,?,?)", membershipId, tenantId, workspaceId, role, sql(now));
		jdbc.update("update tenant_management.organization_registration set status='ACTIVE',tenant_id=?,workspace_id=?,updated_at=?,version=version+1 where id=? and status='PENDING_ACTIVATION'", tenantId, workspaceId, sql(now), registrationId);
		var actor = new Actor(null, null, "SYSTEM", tenantId, workspaceId, correlationId, traceId);
		audit.append(event("ORGANIZATION_ACTIVATED", actor, userId, Map.of("registrationId", registrationId.toString())));
		return new Activation(registrationId.toString(), "ACTIVE", tenantId, workspaceId, userId, Set.of("TENANT_ADMIN", "COMPANY_OWNER"));
	}

	@Override
	@Transactional
	public Registration reject(UUID registrationId, String suppliedOperatorToken, String reason, String correlationId, String traceId) {
		requireOperator(suppliedOperatorToken);
		if (reason == null || reason.isBlank() || reason.length() > 500) throw new IamSecurityException("REJECTION_REASON_REQUIRED");
		int changed = jdbc.update("update tenant_management.organization_registration set status='REJECTED',rejection_reason=?,updated_at=?,version=version+1 where id=? and status='PENDING_ACTIVATION'", reason.trim(), sql(clock.instant()), registrationId);
		if (changed != 1) throw new ApiResourceNotFoundException("pending organization registration");
		var actor = new Actor(null, null, "SYSTEM", null, null, correlationId, traceId);
		audit.append(event("ORGANIZATION_REJECTED", actor, null, Map.of("registrationId", registrationId.toString())));
		return registration(registrationId);
	}

	private void requireOperator(String supplied) {
		if (operatorToken.isBlank() || supplied == null || !MessageDigest.isEqual(operatorToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
			throw new IamSecurityException("SYSTEM_OPERATOR_REQUIRED");
	}

	private void validateProfile(ProfilePatch patch) {
		if (patch == null || patch.displayName() == null || patch.displayName().isBlank() || patch.displayName().length() > 160
				|| patch.preferredLanguage() == null || !Set.of("es", "en").contains(patch.preferredLanguage())
				|| patch.timezone() == null || !isTimezone(patch.timezone())) throw new IamSecurityException("PROFILE_INVALID");
	}

	private void validateRegistration(RegistrationRequest request) {
		if (request == null || blank(request.legalName()) || blank(request.displayName()) || blank(request.operationCategory())
				|| blank(request.storageSiteName()) || blank(request.storageSiteAddress()) || blank(request.founderEmail())
				|| blank(request.founderDisplayName()) || blank(request.workspaceName()) || blank(request.workspaceSlug())
				|| !Set.of("b2bColdChainDistributor", "refrigeratedWarehouseOperator", "foodServiceSupplier", "thirdPartyColdStorage").contains(request.operationCategory())
				|| !Set.of("Starter", "Standard", "Professional", "Enterprise").contains(request.referencePlan()) || !request.termsAccepted()
				|| !request.workspaceSlug().matches("[A-Za-z0-9-]{3,80}") || !request.founderEmail().contains("@")) throw new IamSecurityException("REGISTRATION_INVALID");
	}

	private static boolean isTimezone(String value) { try { java.time.ZoneId.of(value); return value.contains("/") || "UTC".equals(value); } catch (RuntimeException ignored) { return false; } }
	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
	private static String normalizeEmail(String value) { if (blank(value) || value.length() > 254) throw new IamSecurityException("RESET_INVALID"); return value.trim().toLowerCase(Locale.ROOT); }
	private static String opaqueToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
	private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
	private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }
	private static java.sql.Timestamp sql(Instant value) { return java.sql.Timestamp.from(value); }
	private SecurityAuditPort.Event event(String type, Actor actor, UUID target, Map<String, Object> metadata) { return new SecurityAuditPort.Event(type, actor.userId(), target, actor.tenantId(), actor.workspaceId(), actor.surface(), valueOrUnknown(actor.correlationId()), valueOrUnknown(actor.traceId()), clock.instant(), metadata); }
	private static record User(UUID id, String email) {}
	private static record Reset(UUID id, String email, String surface, String status, Instant expiresAt) {}
	private static final class RegistrationRow {
		final UUID id; final String displayName; final String workspaceName; final String workspaceSlug; final String founderEmail; final String founderDisplayName; final String status;
		RegistrationRow(java.sql.ResultSet rs) throws java.sql.SQLException { id=rs.getObject("id",UUID.class); displayName=rs.getString("display_name"); workspaceName=rs.getString("workspace_name"); workspaceSlug=rs.getString("workspace_slug"); founderEmail=rs.getString("founder_email"); founderDisplayName=rs.getString("founder_display_name"); status=rs.getString("status"); }
	}
}
