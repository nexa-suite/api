package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.shared.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.shared.application.port.out.PasswordHashPort;
import com.nexa.api.shared.application.port.out.PasswordVerificationPort;
import com.nexa.api.shared.application.port.out.SecurityNotificationOutboxPort;
import com.nexa.api.shared.domain.model.password.PasswordPolicy;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.InvitationModels;
import com.nexa.api.tenantmanagement.application.port.in.InvitationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.InvitationPersistencePort;
import com.nexa.api.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.ConcurrencyConflictException;
import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.access.AssignableRolePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleCatalog;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationExpiry;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationStatus;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationTokenHash;
import com.nexa.api.tenantmanagement.domain.model.invitation.OrganizationInvitation;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class OrganizationInvitationService implements InvitationUseCase {
	private final InvitationPersistencePort invitations;
	private final TenantConfigurationPort configuration;
	private final OpaqueSecurityTokenPort tokens;
	private final PasswordHashPort hasher;
	private final PasswordVerificationPort passwordVerifier;
	private final SecurityNotificationOutboxPort outbox;
	private final SecurityAuditPort audit;
	private final Clock clock;

	public OrganizationInvitationService(InvitationPersistencePort invitations, TenantConfigurationPort configuration,
			OpaqueSecurityTokenPort tokens, PasswordHashPort hasher, SecurityNotificationOutboxPort outbox,
			SecurityAuditPort audit, Clock clock, PasswordVerificationPort passwordVerifier) {
		this.invitations = Objects.requireNonNull(invitations);
		this.configuration = Objects.requireNonNull(configuration);
		this.tokens = Objects.requireNonNull(tokens);
		this.hasher = Objects.requireNonNull(hasher);
		this.passwordVerifier = Objects.requireNonNull(passwordVerifier);
		this.outbox = Objects.requireNonNull(outbox);
		this.audit = Objects.requireNonNull(audit);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public InvitationModels.InvitationList list(CurrentAccessContext context, int page, int pageSize) {
		read(context);
		int safePage = Math.max(0, page);
		int safeSize = Math.min(100, Math.max(1, pageSize));
		List<InvitationModels.InvitationView> items = invitations.findPage(context.tenantId().toString(), context.workspaceId().toString(), safePage, safeSize)
				.stream().map(this::view).toList();
		return new InvitationModels.InvitationList(items, safePage, safeSize, items.size() == safeSize);
	}

	@Override
	public InvitationModels.InvitationView detail(CurrentAccessContext context, UUID invitationId) {
		read(context);
		return invitations.find(context.tenantId().toString(), context.workspaceId().toString(), invitationId).map(this::view)
				.orElseThrow(() -> new ApiResourceNotFoundException("invitation"));
	}

	@Override
	public InvitationModels.InvitationView create(CurrentAccessContext context, String email, String displayName,
			Set<String> roles, String idempotencyKey, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_MEMBER_INVITE);
		if (idempotencyKey == null || idempotencyKey.isBlank()) throw new InvitationIdempotencyRequiredException();
		String normalizedEmail = normalizeEmail(email);
		String normalizedName = required(displayName, "Display name");
		Set<MembershipRole> roleSet = roles(roles);
		for (MembershipRole role : roleSet) {
			AssignableRolePolicy.requireCanAssign(context.roleCodes(), context.permissionCodes(), RoleCatalog.definitionFor(role));
		}
		if (roleSet.contains(MembershipRole.COMPANY_OWNER)) {
			invitations.lockTenant(context.tenantId().toString());
			if (invitations.activeCompanyOwnerCount(context.tenantId().toString()) >= 1) {
				throw new InvitationConflictException("An active company owner already exists");
			}
		}
		String requestHash = tokens.sha256(normalizedEmail + "|" + normalizedName + "|" + roleSet.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
		if (invitations.idempotencyKeyHasDifferentPayload(context.tenantId().toString(), idempotencyKey, requestHash)) throw new InvitationIdempotencyConflictException();
		var previous = invitations.findIdempotent(context.tenantId().toString(), idempotencyKey, requestHash);
		if (previous.isPresent()) return detail(context, previous.get());
		if (invitations.findActiveMembershipByEmail(context.workspaceId().toString(), normalizedEmail).isPresent()) throw new InvitationConflictException("Membership already exists");
		var settings = configuration.findTenantSecuritySettings(context.tenantId().toString()).orElseThrow();
		if (settings.requiredEmailDomain() != null && !normalizedEmail.endsWith("@" + settings.requiredEmailDomain())) throw new InvitationConflictException("Email domain is not allowed");
		var pending = invitations.findPendingByEmail(context.tenantId().toString(), context.workspaceId().toString(), normalizedEmail);
		if (pending.isPresent()) throw new InvitationConflictException("Active invitation already exists");
		Instant now = clock.instant();
		String token = tokens.generate();
		OrganizationInvitation invitation = OrganizationInvitation.pending(UUID.randomUUID(), new TenantId(context.tenantId().toString()), new WorkspaceId(context.workspaceId().toString()), normalizedEmail, normalizedName,
				new InvitationTokenHash(tokens.sha256(token)), roleSet, new InvitationExpiry(now.plus(Duration.ofHours(settings.invitationExpirationHours()))), new MembershipId(context.membershipId().toString()));
		if (invitations.create(invitation, now) != 1) throw new InvitationConflictException("Invitation could not be created");
		if (invitations.saveIdempotency(context.tenantId().toString(), idempotencyKey, requestHash, invitation.id()) != 1) throw new InvitationIdempotencyConflictException();
		outbox.enqueueInvitation(invitation.email(), invitation.displayName(), token, invitation.expiry().value());
		appendAudit(context, "INVITATION_CREATED", correlationId, Map.of("invitationId", invitation.id().toString(), "roles", roleSet.stream().map(Enum::name).sorted().toList()));
		return detail(context, invitation.id());
	}

	@Override
	public InvitationModels.InvitationView revoke(CurrentAccessContext context, UUID invitationId, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_MEMBER_MANAGE);
		var snapshot = find(context, invitationId);
		OrganizationInvitation invitation = snapshot.invitation();
		try {
			invitation.revoke(clock);
		} catch (TenantManagementInvariantViolation exception) {
			throw new InvitationConflictException("Invitation is not pending");
		}
		if (invitations.updateStatus(context.tenantId().toString(), invitationId, InvitationStatus.REVOKED.name(), clock.instant(), null, expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "INVITATION_REVOKED", correlationId, Map.of("invitationId", invitationId.toString()));
		return detail(context, invitationId);
	}

	@Override
	public InvitationModels.InvitationView resend(CurrentAccessContext context, UUID invitationId, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_MEMBER_MANAGE);
		var snapshot = find(context, invitationId);
		OrganizationInvitation invitation = snapshot.invitation();
		invitation.expire(clock);
		if (invitation.status() != InvitationStatus.PENDING) throw new InvitationConflictException("Invitation is not pending");
		String token = tokens.generate();
		var settings = configuration.findTenantSecuritySettings(context.tenantId().toString()).orElseThrow();
		Instant expiresAt = clock.instant().plus(Duration.ofHours(settings.invitationExpirationHours()));
		InvitationTokenHash replacementHash = new InvitationTokenHash(tokens.sha256(token));
		InvitationExpiry replacementExpiry = new InvitationExpiry(expiresAt);
		try {
			invitation.resend(replacementHash, replacementExpiry, clock);
		} catch (TenantManagementInvariantViolation exception) {
			throw new InvitationConflictException("Invitation is not pending");
		}
		if (invitations.rotateToken(context.tenantId().toString(), invitationId, replacementHash.value(), replacementExpiry.value(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		outbox.enqueueInvitation(invitation.email(), invitation.displayName(), token, expiresAt);
		appendAudit(context, "INVITATION_RESENT", correlationId, Map.of("invitationId", invitationId.toString()));
		return detail(context, invitationId);
	}

	@Override
	public InvitationModels.InvitationAcceptanceResult accept(String token, String password, String displayName, String correlationId) {
		if (token == null || token.isBlank()) throw new InvitationInvalidException();
		InvitationTokenHash presentedHash = new InvitationTokenHash(tokens.sha256(token));
		var snapshot = invitations.findForUpdateByTokenHash(presentedHash.value()).orElseThrow(InvitationInvalidException::new);
		OrganizationInvitation invitation = snapshot.invitation();
		if (!invitation.hasTokenHash(presentedHash)) throw new InvitationInvalidException();
		var settings = configuration.findTenantSecuritySettings(invitation.tenantId().toString()).orElseThrow(InvitationInvalidException::new);
		if (!PasswordPolicy.isValid(password, settings.passwordMinLength())) throw new InvitationInvalidException();
		try {
			invitation.accept(clock);
		} catch (TenantManagementInvariantViolation exception) {
			throw new InvitationInvalidException();
		}
		if (invitations.findActiveMembershipByEmail(invitation.workspaceId().toString(), invitation.email()).isPresent()) throw new InvitationConflictException("Membership already exists");
		UUID userId = invitations.findUserByEmail(invitation.email()).map(existing -> {
			if (!"ACTIVE".equalsIgnoreCase(existing.status())) throw new InvitationConflictException("User account is not active");
			if (!passwordVerifier.matches(password, existing.passwordHash())) throw new InvitationInvalidException();
			return existing.userId();
		}).orElseGet(() -> invitations.createUser(invitation.email(), displayName == null || displayName.isBlank() ? invitation.displayName() : displayName.strip(), hasher.encode(password), clock.instant()));
		UUID membershipId;
		if (invitation.roles().contains(MembershipRole.COMPANY_OWNER)) {
			invitations.lockTenant(invitation.tenantId().toString());
			if (invitations.activeCompanyOwnerCount(invitation.tenantId().toString()) >= 1) {
				throw new InvitationConflictException("An active company owner already exists");
			}
		}
		try {
			membershipId = invitations.createMembership(invitation.tenantId().toString(), invitation.workspaceId().toString(), userId, clock.instant());
		} catch (InvitationPersistencePort.DuplicateMembershipException exception) {
			throw new InvitationConflictException("Membership already exists");
		}
		Set<String> roleNames = invitation.roles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
		invitations.assignRoles(membershipId, invitation.tenantId().toString(), invitation.workspaceId().toString(), roleNames, clock.instant());
		if (invitations.updateStatus(invitation.tenantId().toString(), invitation.id(), InvitationStatus.ACCEPTED.name(), clock.instant(), userId, snapshot.version()) == 0) throw new ConcurrencyConflictException();
		audit.append(new SecurityAuditPort.Event("INVITATION_ACCEPTED", userId, userId, invitation.tenantId().value(), invitation.workspaceId().value(), "PLATFORM", valueOrUnknown(correlationId), "unknown", clock.instant(), Map.of("invitationId", invitation.id().toString())));
		return new InvitationModels.InvitationAcceptanceResult(invitation.id(), userId, invitation.workspaceId().value(), roleNames);
	}

	private InvitationPersistencePort.InvitationSnapshot find(CurrentAccessContext context, UUID invitationId) {
		return invitations.find(context.tenantId().toString(), context.workspaceId().toString(), invitationId).orElseThrow(() -> new ApiResourceNotFoundException("invitation"));
	}
	private InvitationModels.InvitationView view(InvitationPersistencePort.InvitationSnapshot snapshot) {
		OrganizationInvitation invitation = snapshot.invitation();
		return new InvitationModels.InvitationView(invitation.id(), invitation.workspaceId().toString(), invitation.email(), invitation.displayName(), invitation.roles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()), invitation.status().name(), invitation.expiry().value(), snapshot.version(), snapshot.createdAt());
	}
	private static String normalizeEmail(String value) { String normalized = required(value, "Email").toLowerCase(java.util.Locale.ROOT); if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new TenantManagementInvariantViolation("Email is invalid"); return normalized; }
	private static String required(String value, String label) { if (value == null || value.isBlank()) throw new TenantManagementInvariantViolation(label + " is required"); return value.strip(); }
	private static Set<MembershipRole> roles(Set<String> values) { if (values == null || values.isEmpty()) throw new TenantManagementInvariantViolation("At least one invitation role is required"); Set<MembershipRole> result = values.stream().map(MembershipRole::from).collect(Collectors.toUnmodifiableSet()); if (result.contains(MembershipRole.BUYER)) throw new TenantManagementInvariantViolation("Buyer cannot be invited as internal member"); return result; }
	private static void read(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_READ); }
	private void appendAudit(CurrentAccessContext context, String type, String correlationId, Map<String, Object> metadata) { audit.append(new SecurityAuditPort.Event(type, context.userId().value(), null, context.tenantId().value(), context.workspaceId().value(), context.surface().name(), valueOrUnknown(correlationId), "unknown", clock.instant(), metadata)); }
	private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }

	public static final class InvitationInvalidException extends RuntimeException { }
	public static final class InvitationConflictException extends RuntimeException { public InvitationConflictException(String message) { super(message); } }
	public static final class InvitationIdempotencyRequiredException extends RuntimeException { }
	public static final class InvitationIdempotencyConflictException extends RuntimeException { }
}
