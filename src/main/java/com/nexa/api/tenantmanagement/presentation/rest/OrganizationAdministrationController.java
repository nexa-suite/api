package com.nexa.api.tenantmanagement.presentation.rest;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.tenantmanagement.application.model.*;
import com.nexa.api.tenantmanagement.application.port.in.OrganizationAdministrationUseCase;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.ConcurrencyConflictException;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Organization Administration")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationAdministrationController {
	private final OrganizationAdministrationUseCase administration;
	public OrganizationAdministrationController(OrganizationAdministrationUseCase administration) { this.administration = administration; }

	@GetMapping("/organization/current")
	public OrganizationResponse organization(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { return OrganizationResponse.from(administration.organization(context)); }

	@GetMapping("/workspaces")
	public List<WorkspaceSummary> workspaces(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { return administration.workspaces(context); }

	@GetMapping("/workspaces/{workspaceId}")
	public ResponseEntity<WorkspaceDetails> workspace(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String workspaceId) { var details=administration.workspace(context,workspaceId); return ResponseEntity.ok().eTag(etag(details.workspace().version())).body(details); }

	@PatchMapping("/workspaces/{workspaceId}")
	public ResponseEntity<WorkspaceSummary> updateWorkspace(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String workspaceId,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestBody WorkspacePatch patch,HttpServletRequest request) { var result=administration.updateWorkspace(context,workspaceId,patch.name(),patch.status()==null?null:WorkspaceStatus.from(patch.status()),version(ifMatch),correlation(request)); return ResponseEntity.ok().eTag(etag(result.value().version())).body(result.value()); }

	@GetMapping("/workspace-memberships")
	public List<WorkspaceMembershipSummary> memberships(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { return administration.memberships(context); }

	@GetMapping("/workspace-memberships/{membershipId}")
	public ResponseEntity<WorkspaceMembershipSummary> membership(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String membershipId) { var value=administration.membership(context,membershipId); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/workspace-memberships/{membershipId}/role")
	public ResponseEntity<WorkspaceMembershipSummary> role(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String membershipId,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestBody RolePatch patch,HttpServletRequest request) { var result=administration.changeRole(context,membershipId,MembershipRole.from(patch.role()),version(ifMatch),correlation(request)); return ResponseEntity.ok().eTag(etag(result.value().version())).body(result.value()); }

	@PostMapping("/workspace-memberships/{membershipId}/suspensions")
	public ResponseEntity<WorkspaceMembershipSummary> suspend(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String membershipId,@RequestHeader(name="If-Match",required=false) String ifMatch,HttpServletRequest request) { var result=administration.suspendMembership(context,membershipId,version(ifMatch),correlation(request)); return ResponseEntity.ok().eTag(etag(result.value().version())).body(result.value()); }

	@PostMapping("/workspace-memberships/{membershipId}/reactivations")
	public ResponseEntity<WorkspaceMembershipSummary> reactivate(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,@PathVariable String membershipId,@RequestHeader(name="If-Match",required=false) String ifMatch,HttpServletRequest request) { var result=administration.reactivateMembership(context,membershipId,version(ifMatch),correlation(request)); return ResponseEntity.ok().eTag(etag(result.value().version())).body(result.value()); }

	private static long version(String value) { if (value == null || value.isBlank()) throw new PreconditionRequiredException(); try { return Long.parseLong(value.replace("\"", "").trim()); } catch (NumberFormatException exception) { throw new PreconditionRequiredException(); } }
	private static String etag(long version) { return "\"" + version + "\""; }
	private static String correlation(HttpServletRequest request) { Object value=request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }
	public record WorkspacePatch(String name,String status) { }
	public record RolePatch(@NotBlank String role) { }
	public record OrganizationResponse(String id,String name,String slug,String status,String currentWorkspaceId,String currentWorkspaceName,long version) { static OrganizationResponse from(OrganizationSummary value){ return new OrganizationResponse(value.id(),value.name(),value.slug(),value.status(),value.currentWorkspaceId(),value.currentWorkspaceName(),value.version()); } }
	public static final class PreconditionRequiredException extends RuntimeException { }
}
