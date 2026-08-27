package com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.RoleDefinitionModels.CatalogEntry;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.RoleDefinitionModels.CreateCommand;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.RoleDefinitionModels.UpdateCommand;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.RoleDefinitionModels.View;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.RoleDefinitionUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.AssignableRolePolicy;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest.OrganizationAdministrationController.PreconditionRequiredException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Tenant Roles and Permissions")
@SecurityRequirement(name = "bearerAuth")
public final class RoleDefinitionController {
	private static final String CONTEXT = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final RoleDefinitionUseCase roles;

	public RoleDefinitionController(RoleDefinitionUseCase roles) { this.roles = roles; }

	@GetMapping("/permissions/catalog")
	@Operation(operationId = "listPermissionCatalog")
	public List<CatalogEntry> catalog(@RequestAttribute(CONTEXT) CurrentAccessContext context) {
		AssignableRolePolicy.requireCanRead(context.roleCodes(), context.permissionCodes());
		return PermissionCatalog.all().stream().map(CatalogEntry::from).sorted(Comparator.comparing(CatalogEntry::code)).toList();
	}

	@GetMapping("/roles")
	@Operation(operationId = "listRoleDefinitions")
	public List<View> list(@RequestAttribute(CONTEXT) CurrentAccessContext context) {
		return roles.list(context).stream().map(View::from).toList();
	}

	@GetMapping("/roles/{id}")
	@Operation(operationId = "getRoleDefinition")
	public ResponseEntity<View> get(@RequestAttribute(CONTEXT) CurrentAccessContext context, @PathVariable String id) {
		View value = View.from(roles.get(context, id));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/roles")
	@Operation(operationId = "createRoleDefinition")
	public ResponseEntity<View> create(@RequestAttribute(CONTEXT) CurrentAccessContext context,
			@RequestBody CreateCommand command) {
		View value = View.from(roles.create(context, command));
		return ResponseEntity.status(201).eTag(etag(value.version())).body(value);
	}

	@PatchMapping("/roles/{id}")
	@Operation(operationId = "updateRoleDefinition")
	public ResponseEntity<View> update(@RequestAttribute(CONTEXT) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody UpdateCommand command) {
		View value = View.from(roles.update(context, id, command, version(ifMatch)));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@DeleteMapping("/roles/{id}")
	@Operation(operationId = "deactivateRoleDefinition")
	public ResponseEntity<View> deactivate(@RequestAttribute(CONTEXT) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		View value = View.from(roles.deactivate(context, id, version(ifMatch)));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	private static long version(String value) {
		if (value == null || value.isBlank()) throw new PreconditionRequiredException();
		try { return Long.parseLong(value.replace("\"", "").trim()); }
		catch (NumberFormatException exception) { throw new PreconditionRequiredException(); }
	}
	private static String etag(long version) { return "\"" + version + "\""; }
}
