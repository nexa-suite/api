package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SkuIdentifierResolutionQuery;
import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.SkuIdentifierResolutionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Application orchestration for scan-friendly, read-only SKU identifier resolution. */
public final class SkuIdentifierResolutionService implements SkuIdentifierResolutionUseCase {
    private final SkuIdentifierResolutionQuery query;
    private final CatalogAuthorizationPort authorization;

    public SkuIdentifierResolutionService(SkuIdentifierResolutionQuery query,
            CatalogAuthorizationPort authorization) {
        this.query = Objects.requireNonNull(query, "SKU identifier query is required");
        this.authorization = Objects.requireNonNull(authorization, "Catalog authorization is required");
    }

    @Override
    public Resolution resolve(CatalogScope scope, String identifier) {
        Objects.requireNonNull(scope, "Catalog scope is required");
        authorization.require(CatalogPermissions.READ);
        String normalized = normalize(identifier);
        List<SkuIdentifierResolutionQuery.Candidate> candidates = query.resolve(
                scope.tenantId(), scope.workspaceId(), normalized);
        String outcome = candidates.isEmpty() ? "NOT_FOUND" : candidates.size() == 1 ? "RESOLVED" : "AMBIGUOUS";
        String identifierType = type(normalized, candidates);
        SkuIdentifierResolutionQuery.Candidate candidate = candidates.size() == 1 ? candidates.get(0) : null;
        return new Resolution(outcome, identifierType, normalized, candidates.size(),
                candidate == null ? null : candidate.skuId(), candidate == null ? null : candidate.skuCode(),
                candidate == null ? null : candidate.gtin(), candidate == null ? null : candidate.presentation(),
                candidate == null ? null : candidate.unitOfMeasure(), candidate == null ? null : candidate.status());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Identifier is required");
        String normalized = value.trim();
        if (normalized.length() > 160) throw new IllegalArgumentException("Identifier is too long");
        return normalized;
    }

    private static String type(String identifier, List<SkuIdentifierResolutionQuery.Candidate> candidates) {
        boolean sku = candidates.stream().anyMatch(value -> identifier.equals(value.skuCode()));
        boolean gtin = candidates.stream().anyMatch(value -> identifier.equals(value.gtin()));
        if (sku && gtin) return "SKU_CODE_AND_GTIN";
        if (gtin || identifier.matches("[0-9]{8,14}")) return "GTIN";
        return "SKU_CODE";
    }

}
