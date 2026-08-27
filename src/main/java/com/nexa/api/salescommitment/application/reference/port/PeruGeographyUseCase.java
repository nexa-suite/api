package com.nexa.api.salescommitment.application.reference.port;

import com.nexa.api.salescommitment.application.reference.model.ReferenceOptionView;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyPath;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.Optional;

public interface PeruGeographyUseCase {
    List<ReferenceOptionView> list(CurrentAccessContext context, PeruGeographyLevel level, String parentCode);

    Optional<PeruGeographyPath> resolve(CurrentAccessContext context, String departmentCode,
                                        String provinceCode, String districtCode);
}
