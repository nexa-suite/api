package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
import com.nexa.api.catalogcommercialpolicy.application.port.in.SkuIdentifierResolutionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SkuIdentifierResolutionQuery;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcTenantCatalogDetailQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantBoundSkuIdentifierReadTests {
    private final TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantCustomerAccountQueryFactory accounts = mock(TenantCustomerAccountQueryFactory.class);
    private final TenantCatalogAvailabilityAdapterFactory availability = mock(TenantCatalogAvailabilityAdapterFactory.class);
    private final TenantBoundCatalogDetailReader reader = new TenantBoundCatalogDetailReader(router, accounts,
            new JdbcTenantCatalogDetailQueryFactory(Clock.systemUTC()), availability);
    private final CurrentAccessContext context = mock(CurrentAccessContext.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void existingResolverUsesOnlyTheRoutedTenantJdbcAndExactScope() {
        authorize();
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(router.inTransaction(eq(context), any())).thenAnswer(invocation ->
                invocation.<TenantBusinessDatabaseWork<SkuIdentifierResolutionUseCase.Resolution>>getArgument(1).execute(jdbc));
        var sku = new SkuIdentifierResolutionQuery.Candidate(UUID.randomUUID(), "PROD-0003", null,
                "MOLDE 3KG", "UNIT", "ACTIVE", true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(workspace),
                eq("PROD-0003"), eq("PROD-0003"))).thenReturn(List.of(sku));
        var result = reader.resolveSkuIdentifier(context, "  PROD-0003  ");
        assertThat(result.outcome()).isEqualTo("RESOLVED");
        assertThat(result.skuId()).isEqualTo(sku.skuId());
        verify(jdbc).query(contains("status='ACTIVE' and visible"), any(RowMapper.class),
                eq(tenant), eq(workspace), eq("PROD-0003"), eq("PROD-0003"));
        verifyNoInteractions(accounts, availability);
    }

    @Test void missingPermissionNeverRoutesOrQueries() {
        assertThatThrownBy(() -> reader.resolveSkuIdentifier(context, "PROD-0003"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(router, jdbc, accounts, availability);
    }

    @Test void missingContextNeverRoutesOrQueries() {
        authorize();
        assertThatThrownBy(() -> reader.resolveSkuIdentifier(null, "PROD-0003"))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(router, jdbc, accounts, availability);
    }

    @Test void controllerSelectsTheBoundedReaderWithoutTheManagementComposition() {
        var reads = mock(org.springframework.beans.factory.ObjectProvider.class);
        var management = mock(org.springframework.beans.factory.ObjectProvider.class);
        var legacy = mock(SkuIdentifierResolutionUseCase.class);
        var bounded = mock(com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogHttpReadPort.class);
        var result = mock(SkuIdentifierResolutionUseCase.Resolution.class);
        when(reads.getIfAvailable()).thenReturn(bounded);
        when(bounded.resolveSkuIdentifier(context, "PROD-0003")).thenReturn(result);
        var controller = new com.nexa.api.catalogcommercialpolicy.presentation.rest.SkuIdentifierResolutionController(
                legacy, management, reads);
        assertThat(controller.resolve(context, "PROD-0003")).isSameAs(result);
        verify(bounded).resolveSkuIdentifier(context, "PROD-0003");
        verifyNoInteractions(management, legacy, router, jdbc);
    }

    @Test void routerScopeRejectionNeverReachesTheQuery() {
        authorize();
        when(router.inTransaction(eq(context), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("Tenant scope mismatch"));
        assertThatThrownBy(() -> reader.resolveSkuIdentifier(context, "PROD-0003"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(jdbc, accounts, availability);
    }

    @Test void controllerKeepsTheExistingManagementRequestWhenNoReaderIsPresent() {
        var reads = mock(org.springframework.beans.factory.ObjectProvider.class);
        var providers = mock(org.springframework.beans.factory.ObjectProvider.class);
        var management = mock(com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort.class);
        when(providers.getIfAvailable()).thenReturn(management);
        var legacy = mock(SkuIdentifierResolutionUseCase.class);
        var controller = new com.nexa.api.catalogcommercialpolicy.presentation.rest.SkuIdentifierResolutionController(
                legacy, providers, reads);
        controller.resolve(context, "PROD-0003");
        verify(management).inRequest(eq(context), any());
        verifyNoInteractions(legacy, router, jdbc);
    }

    private void authorize() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "warehouse", "unused", List.of(new SimpleGrantedAuthority("catalog:read"))));
    }
}
