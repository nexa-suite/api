package com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest;

import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** Preserves the compatibility route while failing closed when Tenant persistence is not enabled. */
@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "false", matchIfMissing = true)
@Tag(name = "Tenant Configuration")
@SecurityRequirement(name = "bearerAuth")
public final class CentralTenantNotificationSettingsController {
    private static final String ACCESS_CONTEXT_ATTRIBUTE =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    @GetMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> notifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId) {
        throw unavailable();
    }

    @PatchMapping("/workspaces/{workspaceId}/notifications")
    public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> updateNotifications(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @PathVariable String workspaceId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody TenantConfigurationModels.NotificationSettingsView request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        throw unavailable();
    }

    private static TechnicalFailureException unavailable() {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                "Tenant notification database routing is unavailable");
    }
}
