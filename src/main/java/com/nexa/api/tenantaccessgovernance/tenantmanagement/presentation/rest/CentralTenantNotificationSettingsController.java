package com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest;

import com.nexa.api.shared.context.RequestMetadata;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.OrganizationPreconditionRequiredException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.TenantConfigurationUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Central compatibility path retained while tenant notification persistence is disabled. */
@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "false", matchIfMissing = true)
@Tag(name = "Tenant Configuration")
@SecurityRequirement(name = "bearerAuth")
public final class CentralTenantNotificationSettingsController {
    private static final String ACCESS_CONTEXT_ATTRIBUTE =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final TenantConfigurationUseCase configuration;

    public CentralTenantNotificationSettingsController(TenantConfigurationUseCase configuration) {
        this.configuration = configuration;
    }

    @GetMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> notifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId) {
        var value = configuration.notificationSettings(context, workspaceId);
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> updateNotifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody TenantConfigurationModels.NotificationSettingsView request,
            HttpServletRequest servletRequest) {
        var value = configuration.updateNotificationSettings(context, workspaceId, request,
                version(ifMatch), correlation(servletRequest));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new OrganizationPreconditionRequiredException();
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        } catch (NumberFormatException exception) {
            throw new OrganizationPreconditionRequiredException();
        }
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(RequestMetadata.CORRELATION_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
