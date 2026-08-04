package com.nexa.api.tenantmanagement.presentation.rest;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.InvitationModels;
import com.nexa.api.tenantmanagement.application.port.in.InvitationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

import static com.nexa.api.tenantmanagement.presentation.rest.OrganizationAdministrationController.PreconditionRequiredException;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Organization Invitations")
@SecurityRequirement(name = "bearerAuth")
public final class OrganizationInvitationController {
	private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final InvitationUseCase invitations;

	public OrganizationInvitationController(InvitationUseCase invitations) { this.invitations = invitations; }

	@GetMapping("/organization-invitations")
	@Operation(operationId = "listOrganizationInvitations")
	public InvitationModels.InvitationList list(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int pageSize) { return invitations.list(context, page, pageSize); }

	@GetMapping("/organization-invitations/{invitationId}")
	@Operation(operationId = "getOrganizationInvitation")
	public ResponseEntity<InvitationModels.InvitationView> detail(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context, @PathVariable UUID invitationId) {
		var value = invitations.detail(context, invitationId);
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/organization-invitations")
	@Operation(operationId = "createOrganizationInvitation")
	public ResponseEntity<InvitationModels.InvitationView> create(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CreateInvitationRequest request, HttpServletRequest servletRequest) {
		var value = invitations.create(context, request.email(), request.displayName(), request.roles(), idempotencyKey, correlation(servletRequest));
		return ResponseEntity.status(201).eTag(etag(value.version())).body(value);
	}

	@PostMapping("/organization-invitations/{invitationId}/revocations")
	@Operation(operationId = "revokeOrganizationInvitation")
	public ResponseEntity<InvitationModels.InvitationView> revoke(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable UUID invitationId, @RequestHeader(name = "If-Match", required = false) String ifMatch, HttpServletRequest servletRequest) {
		var value = invitations.revoke(context, invitationId, version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/organization-invitations/{invitationId}/resends")
	@Operation(operationId = "resendOrganizationInvitation")
	public ResponseEntity<InvitationModels.InvitationView> resend(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable UUID invitationId, @RequestHeader(name = "If-Match", required = false) String ifMatch, HttpServletRequest servletRequest) {
		var value = invitations.resend(context, invitationId, version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/organization-invitation-acceptances")
	@Operation(operationId = "acceptOrganizationInvitation")
	public ResponseEntity<InvitationModels.InvitationAcceptanceResult> accept(@RequestBody AcceptInvitationRequest request, HttpServletRequest servletRequest) {
		return ResponseEntity.status(201).body(invitations.accept(request.token(), request.password(), request.displayName(), correlation(servletRequest)));
	}

	private static long version(String value) {
		if (value == null || value.isBlank()) throw new PreconditionRequiredException();
		try { return Long.parseLong(value.replace("\"", "").trim()); } catch (NumberFormatException exception) { throw new PreconditionRequiredException(); }
	}
	private static String etag(long version) { return "\"" + version + "\""; }
	private static String correlation(HttpServletRequest request) { Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }

	public record CreateInvitationRequest(String email, String displayName, Set<String> roles) { }
	public record AcceptInvitationRequest(String token, String password, String displayName) { }
}
