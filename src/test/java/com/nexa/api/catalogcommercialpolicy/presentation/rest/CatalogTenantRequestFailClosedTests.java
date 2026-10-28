package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.shared.application.error.TechnicalFailureException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTenantRequestFailClosedTests {
    @Test
    void missingTenantCatalogAdapterDoesNotUseCentralCompatibilityPath() {
        AtomicBoolean centralPathCalled = new AtomicBoolean();

        assertThatThrownBy(() -> CatalogHttpSupport.tenantRequest(null, null,
                () -> {
                    centralPathCalled.set(true);
                    return "central";
                }, request -> "tenant"))
                .isInstanceOf(TechnicalFailureException.class)
                .extracting(exception -> ((TechnicalFailureException) exception).kind())
                .isEqualTo(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE);

        assertThat(centralPathCalled).isFalse();
    }

    @Test
    void missingTenantCatalogReadAdapterDoesNotUseCentralRead() {
        assertThatThrownBy(() -> CatalogHttpSupport.tenantCatalogReadRequest(null, null,
                tenantRead -> "tenant"))
                .isInstanceOf(TechnicalFailureException.class)
                .extracting(exception -> ((TechnicalFailureException) exception).kind())
                .isEqualTo(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE);
    }
}
