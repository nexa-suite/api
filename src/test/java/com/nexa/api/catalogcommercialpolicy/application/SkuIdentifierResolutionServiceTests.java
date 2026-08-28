package com.nexa.api.catalogcommercialpolicy.application;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SkuIdentifierResolutionQuery;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.service.SkuIdentifierResolutionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkuIdentifierResolutionServiceTests {
    private static final UUID TENANT = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
    private static final UUID WORKSPACE = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");

    @Test
    void resolvesOnlyScopedVisibleCatalogCandidates() {
        CatalogScope scope = scope();
        SkuIdentifierResolutionQuery query = mock(SkuIdentifierResolutionQuery.class);
        CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
        UUID skuId = UUID.randomUUID();
        when(query.resolve(TENANT, WORKSPACE, "SKU-001")).thenReturn(List.of(
                new SkuIdentifierResolutionQuery.Candidate(skuId, "SKU-001", "07501234567890",
                        "Cheese 1 kg", "UNIT", "ACTIVE", true)));

        SkuIdentifierResolutionService.Resolution result = new SkuIdentifierResolutionService(query, authorization)
                .resolve(scope, "  SKU-001 ");

        assertThat(result.outcome()).isEqualTo("RESOLVED");
        assertThat(result.identifierType()).isEqualTo("SKU_CODE");
        assertThat(result.normalizedIdentifier()).isEqualTo("SKU-001");
        assertThat(result.skuId()).isEqualTo(skuId);
        verify(authorization).require("catalog:read");
    }

    @Test
    void exposesAmbiguityWithoutSelectingAHiddenCandidate() {
        CatalogScope scope = scope();
        SkuIdentifierResolutionQuery query = mock(SkuIdentifierResolutionQuery.class);
        CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
        when(query.resolve(TENANT, WORKSPACE, "07501234567890")).thenReturn(List.of(
                new SkuIdentifierResolutionQuery.Candidate(UUID.randomUUID(), "SKU-A", "07501234567890",
                        "A", "UNIT", "ACTIVE", true),
                new SkuIdentifierResolutionQuery.Candidate(UUID.randomUUID(), "SKU-B", "07501234567890",
                        "B", "UNIT", "ACTIVE", true)));

        SkuIdentifierResolutionService.Resolution result = new SkuIdentifierResolutionService(query, authorization)
                .resolve(scope, "07501234567890");

        assertThat(result.outcome()).isEqualTo("AMBIGUOUS");
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.skuId()).isNull();
        assertThat(result.identifierType()).isEqualTo("GTIN");
    }

    private static CatalogScope scope() {
        return new CatalogScope(TENANT, WORKSPACE);
    }
}
