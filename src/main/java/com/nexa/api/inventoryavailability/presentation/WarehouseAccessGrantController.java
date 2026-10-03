package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.shared.context.RequestMetadata;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseAccessGrantView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** BC-05 HTTP composition validates Warehouse identity before delegating BC-01 grant commands. */
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}/access-grants")
@Profile("!test")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class WarehouseAccessGrantController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final WarehouseSelectionQuery warehouses;
    private final WarehouseObjectAccess objectAccess;

    public WarehouseAccessGrantController(WarehouseSelectionQuery warehouses, WarehouseObjectAccess objectAccess) {
        this.warehouses = warehouses;
        this.objectAccess = objectAccess;
    }

    @GetMapping
    @Operation(operationId = "listWarehouseAccessGrants", summary = "List Warehouse access grants",
            description = "Lists grants for an existing Warehouse in the active tenant workspace. Requires current tenant.role.assign authority.")
    public List<WarehouseAccessGrantView> list(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID warehouseId) {
        requireWarehouse(context, warehouseId);
        return objectAccess.grants(context, warehouseId);
    }

    @PostMapping
    @Operation(operationId = "grantWarehouseAccess", summary = "Grant Warehouse access",
            description = "Grants a Warehouse to an active internal workforce membership. If-Match is required to reactivate a revoked grant.")
    public ResponseEntity<WarehouseAccessGrantView> grant(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID warehouseId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody GrantRequest request, HttpServletRequest servletRequest) {
        requireWarehouse(context, warehouseId);
        WarehouseAccessGrantView grant = objectAccess.grant(context, warehouseId, request.membershipId(),
                optionalVersion(ifMatch), correlation(servletRequest));
        return ResponseEntity.ok().eTag(etag(grant.version())).body(grant);
    }

    @DeleteMapping("/{membershipId}")
    @Operation(operationId = "revokeWarehouseAccess", summary = "Revoke Warehouse access",
            description = "Revokes the membership's Warehouse grant using its current version.")
    public ResponseEntity<WarehouseAccessGrantView> revoke(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID warehouseId,
            @PathVariable UUID membershipId,
            @Parameter(name = "If-Match", description = "Current grant version ETag, for example \\\"0\\\".", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            HttpServletRequest servletRequest) {
        requireWarehouse(context, warehouseId);
        WarehouseAccessGrantView grant = objectAccess.revoke(context, warehouseId, membershipId,
                requiredVersion(ifMatch), correlation(servletRequest));
        return ResponseEntity.ok().eTag(etag(grant.version())).body(grant);
    }

    private void requireWarehouse(CurrentAccessContext context, UUID warehouseId) {
        objectAccess.authorizeAdministration(context);
        if (!warehouses.existsInScope(context.tenantId().value(), context.workspaceId().value(), warehouseId)) {
            throw new WarehouseOperationsService.WarehouseException("WAREHOUSE_NOT_FOUND", true);
        }
    }

    private static Long optionalVersion(String value) {
        if (value == null || value.isBlank()) return null;
        return parseVersion(value);
    }

    private static long requiredVersion(String value) {
        if (value == null || value.isBlank()) throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        return parseVersion(value);
    }

    private static long parseVersion(String value) {
        try {
            long version = Long.parseLong(value.replace("\"", "").trim());
            if (version < 0) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException exception) {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(RequestMetadata.CORRELATION_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    public record GrantRequest(@NotNull @Schema(description = "Active internal workspace membership receiving access") UUID membershipId) { }
}
