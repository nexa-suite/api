package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogHttpReadPort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CloudTenantReadConfigurationTests {
    @Test
    void onlyExplicitCloudReadCompositionReplacesTheClientAccountFallback() {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClientAccountUseCase.class))
                    .isNotInstanceOf(CloudBuyerSelfAccountReadUseCase.class);
            assertThat(context).doesNotHaveBean("cloudBuyerSelfAccountRead");
        });
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClientAccountUseCase.class)).isInstanceOf(CloudBuyerSelfAccountReadUseCase.class);
            assertThat(context.getBean(TenantCatalogHttpReadPort.class)).isInstanceOf(TenantBoundCatalogDetailReader.class);
            assertThat(context).doesNotHaveBean("centralFallbackClientAccountUseCase")
                    .hasBean("centralFallbackClientAccountAddressUseCase").hasBean("centralFallbackFieldVisitUseCase");
            assertThatThrownBy(() -> context.getBean(ClientAccountAddressUseCase.class)
                    .list(mock(CurrentAccessContext.class), "test-account"))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            assertThatThrownBy(() -> context.getBean(FieldVisitUseCase.class)
                    .listForCustomer(mock(CurrentAccessContext.class), "test-account"))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(context.getBean("clientAccountUseCase"), context.getBean("clientAccountAddressUseCase"),
                    context.getBean("fieldVisitService"), context.getBean(TenantBusinessDatabaseRouter.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "nexa.tenant-business.cloud-router.enabled", "nexa.tenant-business.cloud-read.enabled",
            "nexa.tenant-business.catalog-detail-read.enabled" })
    void partialOptInNeverRemovesTheCentralClientAccountGuard(String missingProperty) {
        enabled().withPropertyValues(missingProperty + "=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("cloudBuyerSelfAccountRead")
                    .hasBean("centralFallbackClientAccountUseCase");
            assertThatThrownBy(() -> context.getBean(ClientAccountUseCase.class).buyerDetail(mock(CurrentAccessContext.class)))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(context.getBean("clientAccountUseCase"), context.getBean(TenantBusinessDatabaseRouter.class));
        });
    }

    @Test
    void cloudReadDoesNotActivateOnAnotherRuntimeProfile() {
        enabled().withPropertyValues("spring.profiles.active=s3").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("cloudBuyerSelfAccountRead")
                    .hasBean("centralFallbackClientAccountUseCase");
        });
    }

    @Test
    void accountReadsDelegateOnlyToTheVerifiedTenantUseCaseAndEveryOtherMethodFailsClosed() throws Exception {
        ClientAccountUseCase tenant = mock(ClientAccountUseCase.class);
        var read = new CloudBuyerSelfAccountReadUseCase(tenant);
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        ClientAccountView result = mock(ClientAccountView.class);
        when(tenant.buyerDetail(context)).thenReturn(result);
        assertThat(read.buyerDetail(context)).isSameAs(result);
        verify(tenant).buyerDetail(context);
        var page = new CustomerAccountPage<ClientAccountView>(List.of(result), 0, 20, 1);
        when(tenant.list(context, "customer", "ACTIVE", 0, 20)).thenReturn(page);
        when(tenant.detail(context, "account-id")).thenReturn(result);
        assertThat(read.list(context, "customer", "ACTIVE", 0, 20)).isSameAs(page);
        assertThat(read.detail(context, "account-id")).isSameAs(result);
        verify(tenant).list(context, "customer", "ACTIVE", 0, 20);
        verify(tenant).detail(context, "account-id");
        for (Method method : ClientAccountUseCase.class.getMethods()) {
            if (Set.of("buyerDetail", "list", "detail").contains(method.getName())) continue;
            Object[] arguments = new Object[method.getParameterCount()];
            for (int index = 0; index < arguments.length; index++) {
                Class<?> type = method.getParameterTypes()[index];
                if (type == CurrentAccessContext.class) arguments[index] = context;
                else if (type == int.class) arguments[index] = 0;
                else if (type == long.class) arguments[index] = 0L;
            }
            assertThatThrownBy(() -> {
                try { method.invoke(read, arguments); }
                catch (InvocationTargetException exception) { throw exception.getCause(); }
            }).isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
        }
        verifyNoMoreInteractions(tenant);
    }

    @Test
    void missingBuyerScopeCannotOpenTenantConnections() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(ClientAccountUseCase.class).buyerDetail(null))
                    .isInstanceOf(RuntimeException.class);
            verifyNoInteractions(context.getBean(TenantBusinessDatabaseRouter.class),
                    context.getBean(TenantCustomerRelationshipsCompositionFactory.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "BUYER", "TENANT_ADMIN" })
    void administrativeAccountReadsRejectUnqualifiedRolesBeforeRouting(String role) {
        var router = mock(TenantBusinessDatabaseRouter.class);
        var compositions = mock(TenantCustomerRelationshipsCompositionFactory.class);
        var tenant = new TenantBoundCustomerRelationshipsUseCase(router, compositions,
                mock(BuyerMembershipDirectory.class), mock(ActiveSalesMembershipDirectory.class),
                mock(TenantBusinessTraceabilityBindingsFactory.class));
        var read = new CloudBuyerSelfAccountReadUseCase(tenant);
        var context = mock(CurrentAccessContext.class);
        when(context.hasRole(MembershipRole.valueOf(role))).thenReturn(true);
        assertThatThrownBy(() -> read.list(context, null, null, 0, 20))
                .isInstanceOf(AccessPolicyViolation.class);
        assertThatThrownBy(() -> read.detail(context, "account-id"))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(router, compositions);
    }

    @Test
    void administrativeAccountReadsRequireMatchingRequestScopeBeforeRouting() {
        var router = mock(TenantBusinessDatabaseRouter.class);
        var compositions = mock(TenantCustomerRelationshipsCompositionFactory.class);
        var tenant = new TenantBoundCustomerRelationshipsUseCase(router, compositions,
                mock(BuyerMembershipDirectory.class), mock(ActiveSalesMembershipDirectory.class),
                mock(TenantBusinessTraceabilityBindingsFactory.class));
        var read = new CloudBuyerSelfAccountReadUseCase(tenant);
        var context = mock(CurrentAccessContext.class);
        var tenantId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        when(context.tenantId()).thenReturn(new TenantId(tenantId));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
        RlsRequestScope.clear();
        try {
            assertThatThrownBy(() -> read.list(context, null, null, 0, 20))
                    .isInstanceOf(AccessPolicyViolation.class);
            RlsRequestScope.set(tenantId, UUID.randomUUID());
            assertThatThrownBy(() -> read.detail(context, "account-id"))
                    .isInstanceOf(AccessPolicyViolation.class);
            verifyNoInteractions(router, compositions);
        } finally {
            RlsRequestScope.clear();
        }
    }

    private ApplicationContextRunner enabled() {
        return context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
                "nexa.tenant-business.cloud-read.enabled=true", "nexa.tenant-business.catalog-detail-read.enabled=true");
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(CloudTenantReadConfiguration.class, TenantBoundCatalogDetailReadConfiguration.class,
                        CentralTenantUseCaseFallbackGuardConfiguration.class, CentralTenantBusinessDataAccessGuardConfiguration.class,
                        CentralTenantUseCaseFallbackGuardConfigurationTests.CentralDelegates.class)
                .withBean(TenantBusinessDatabaseRouter.class, () -> mock(TenantBusinessDatabaseRouter.class))
                .withBean(TenantCustomerRelationshipsCompositionFactory.class, () -> mock(TenantCustomerRelationshipsCompositionFactory.class))
                .withBean(BuyerMembershipDirectory.class, () -> mock(BuyerMembershipDirectory.class))
                .withBean(ActiveSalesMembershipDirectory.class, () -> mock(ActiveSalesMembershipDirectory.class))
                .withBean(TenantBusinessTraceabilityBindingsFactory.class, () -> mock(TenantBusinessTraceabilityBindingsFactory.class))
                .withBean(TenantCustomerAccountQueryFactory.class, () -> mock(TenantCustomerAccountQueryFactory.class))
                .withBean(TenantCatalogDetailQueryFactory.class, () -> mock(TenantCatalogDetailQueryFactory.class))
                .withBean(TenantCatalogAvailabilityAdapterFactory.class, () -> mock(TenantCatalogAvailabilityAdapterFactory.class))
                .withPropertyValues("spring.profiles.active=tenant-cloud");
    }
}
