package com.nexa.api.sales.application.reference.port;

import com.nexa.api.sales.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.sales.domain.model.reference.PeruGeographyOption;
import com.nexa.api.sales.domain.model.reference.PeruGeographyPath;

import java.util.List;
import java.util.Optional;

/** Persistence contract for the canonical Peru department/province/district hierarchy. */
public interface PeruGeographyPersistencePort {
    List<PeruGeographyOption> list(PeruGeographyLevel level, String parentCode);

    Optional<PeruGeographyPath> resolve(String departmentCode, String provinceCode, String districtCode);
}
