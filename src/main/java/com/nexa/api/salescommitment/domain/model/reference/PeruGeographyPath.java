package com.nexa.api.salescommitment.domain.model.reference;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.Objects;

public record PeruGeographyPath(PeruGeographyOption department, PeruGeographyOption province,
                               PeruGeographyOption district) {
    public PeruGeographyPath {
        department = Objects.requireNonNull(department, "Department is required");
        province = Objects.requireNonNull(province, "Province is required");
        district = Objects.requireNonNull(district, "District is required");
        if (department.level() != PeruGeographyLevel.DEPARTMENT
                || province.level() != PeruGeographyLevel.PROVINCE
                || district.level() != PeruGeographyLevel.DISTRICT
                || !department.code().equals(province.parentCode())
                || !province.code().equals(district.parentCode())) {
            throw new SalesInvariantViolation("Peru geography hierarchy is inconsistent");
        }
    }
}
