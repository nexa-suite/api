package com.nexa.api.sales.application.reference.port;

import com.nexa.api.sales.application.reference.model.ReferenceOptionView;
import com.nexa.api.sales.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.sales.domain.model.reference.PeruGeographyPath;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.Optional;

public interface PeruGeographyUseCase {
    List<ReferenceOptionView> list(CurrentAccessContext context, PeruGeographyLevel level, String parentCode);

    Optional<PeruGeographyPath> resolve(CurrentAccessContext context, String departmentCode,
                                        String provinceCode, String districtCode);
}
