package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuiltInRolePermissionQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Exposes the owner-maintained built-in role policy as a narrow query. */
@Component
public final class BuiltInRolePermissionQueryAdapter implements BuiltInRolePermissionQuery {
    @Override
    public boolean allows(MembershipRole role, PermissionKey permission) {
        return PermissionCatalog.forBuiltInRole(Objects.requireNonNull(role, "Membership role is required"))
                .contains(Objects.requireNonNull(permission, "Permission is required"));
    }
}
