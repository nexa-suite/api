package com.nexa.api.bootstrap.runtime.presentation;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.publicapi.TenantNotificationSettingsCommands;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.context.RequestMetadata;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.OrganizationPreconditionRequiredException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
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

import java.util.UUID;

/** HTTP composition edge for tenant-bound BC-10 notification preferences. */
@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "true")
@Tag(name = "Tenant Configuration")
@SecurityRequirement(name = "bearerAuth")
public final class TenantNotificationSettingsController {
    private static final String ACCESS_CONTEXT_ATTRIBUTE =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final TenantNotificationSettingsCommands settings;

    public TenantNotificationSettingsController(TenantNotificationSettingsCommands settings) {
        this.settings = settings;
    }

    @GetMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<NotificationPreferencesView> notifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId) {
        var value = settings.settings(context, workspaceUuid(workspaceId));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<NotificationPreferencesView> updateNotifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody NotificationPreferencesView request, HttpServletRequest servletRequest) {
        long expectedVersion = version(ifMatch);
        var normalizedRequest = new NotificationPreferencesView(request.preferences(), expectedVersion);
        var value = settings.updateSettings(context, workspaceUuid(workspaceId), normalizedRequest,
                expectedVersion, correlation(servletRequest));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    private static UUID workspaceUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiResourceNotFoundException("workspace");
        }
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
