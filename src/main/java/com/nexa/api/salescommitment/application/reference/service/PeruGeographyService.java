package com.nexa.api.salescommitment.application.reference.service;

import com.nexa.api.salescommitment.application.reference.model.ReferenceOptionView;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyUseCase;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyPath;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.List;
import java.util.Optional;

public final class PeruGeographyService implements PeruGeographyUseCase {
    private final PeruGeographyPersistencePort persistence;

    public PeruGeographyService(PeruGeographyPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Override
    public List<ReferenceOptionView> list(CurrentAccessContext context, PeruGeographyLevel level, String parentCode) {
        readAccess(context);
        return persistence.list(level, parentCode).stream()
                .map(value -> new ReferenceOptionView(value.id(), value.code(), value.label(), value.parentCode(), value.active()))
                .toList();
    }

    @Override
    public Optional<PeruGeographyPath> resolve(CurrentAccessContext context, String departmentCode,
                                               String provinceCode, String districtCode) {
        readAccess(context);
        return persistence.resolve(departmentCode, provinceCode, districtCode);
    }

    private static void readAccess(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)) context.requirePermission(Permission.SALES_BUYER_READ);
        else context.requirePermission(Permission.SALES_READ);
    }
}
