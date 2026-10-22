package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadTimeViolationException;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.MaterialChangePersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.SellableSkuSnapshotLookupPort;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.salescommitment.application.port.out.WarehouseReferencePort;
import com.nexa.api.salescommitment.application.publicapi.MapRoutingPort;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDeliveryLeadTimePolicyProviderConfiguration;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotConflictException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SalesRuntimeDeliveryLeadTimeWiringTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SalesRuntimeConfiguration.class,
                    TenantBusinessDatabaseDeliveryLeadTimePolicyProviderConfiguration.class, TestDependencies.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("s3"));

    @Test
    void runtimeBuyerAndSalesServicesResolvePolicyFromTheirActiveWorkspace() {
        UUID tenantValue = UUID.randomUUID();
        UUID buyerWorkspaceValue = UUID.randomUUID();
        UUID salesWorkspaceValue = UUID.randomUUID();
        Map<UUID, Integer> policyByWorkspace = Map.of(buyerWorkspaceValue, 5, salesWorkspaceValue, 7);

        contextRunner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
            org.assertj.core.api.Assertions.assertThat(context.getEnvironment().getActiveProfiles()).contains("s3");
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(PurchaseRequestUseCase.class);
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(ManualSalesOrderUseCase.class);
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(
                    com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTimePolicyProvider.class);

            OperationalSettingsAccess settings = context.getBean(OperationalSettingsAccess.class);
            when(settings.findDeliveryLeadTimePolicy(any(TenantId.class), any(WorkspaceId.class)))
                    .thenAnswer(invocation -> {
                        TenantId tenant = invocation.getArgument(0);
                        WorkspaceId workspace = invocation.getArgument(1);
                        Integer days = policyByWorkspace.get(workspace.value());
                        return days == null ? Optional.empty() : Optional.of(
                                new OperationalSettingsAccess.DeliveryLeadTimePolicySource(tenant, workspace,
                                        OperationalSettingsAccess.SourceState.PRESENT, 1L, days));
                    });
            CustomerAccountQuery accounts = context.getBean(CustomerAccountQuery.class);
            CurrentAccessContext buyerContext = context(tenantValue, buyerWorkspaceValue, MembershipRole.BUYER);
            String accountId = UUID.randomUUID().toString();
            when(accounts.findBuyerReference(tenantValue.toString(), buyerWorkspaceValue.toString(),
                    buyerContext.membershipId().toString()))
                    .thenReturn(Optional.of(new CustomerAccountReference(accountId, "ACTIVE")));

            LocalDate fourDaysFromToday = LocalDate.now(Clock.systemUTC()).plusDays(4);
            assertThatThrownBy(() -> context.getBean(PurchaseRequestUseCase.class).create(buyerContext, accountId,
                    "NORMAL", fourDaysFromToday, "standard", "CASH", null,
                    List.of(new PurchaseRequestUseCase.RequestedLine("CAT-0002", java.math.BigDecimal.ONE,
                            "UNIT", null))))
                    .isInstanceOfSatisfying(MinimumDeliveryLeadTimeViolationException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.minimumCalendarDays()).isEqualTo(5));
            verifyNoInteractions(context.getBean(PurchaseRequestPersistencePort.class));

            CurrentAccessContext salesContext = context(tenantValue, salesWorkspaceValue, MembershipRole.SALES);
            LocalDate sixDaysFromToday = LocalDate.now(Clock.systemUTC()).plusDays(6);
            CreateManualSalesOrderCommand command = new CreateManualSalesOrderCommand(null, null, null,
                    sixDaysFromToday, null, null, null, null, null, null, null, List.of());
            assertThatThrownBy(() -> context.getBean(ManualSalesOrderUseCase.class).create(salesContext, command,
                    "lead-time-wiring"))
                    .isInstanceOfSatisfying(MinimumDeliveryLeadTimeViolationException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.minimumCalendarDays()).isEqualTo(7));
            ManualSalesOrderPersistencePort salesOrderPersistence = context.getBean(ManualSalesOrderPersistencePort.class);
            verify(salesOrderPersistence).findByIdempotency(any(), any(), any(), any(), any());
            verifyNoMoreInteractions(salesOrderPersistence);
            verifyNoInteractions(context.getBean(ClientAccountCommercialPort.class),
                    context.getBean(CustomerAddressQuery.class), context.getBean(WarehouseReferencePort.class),
                    context.getBean(MapRoutingPort.class));
            verify(settings).findDeliveryLeadTimePolicy(new TenantId(tenantValue), new WorkspaceId(buyerWorkspaceValue));
            verify(settings).findDeliveryLeadTimePolicy(new TenantId(tenantValue), new WorkspaceId(salesWorkspaceValue));

            CurrentAccessContext unconfiguredWorkspace = context(tenantValue, UUID.randomUUID(), MembershipRole.SALES);
            assertThatThrownBy(() -> context.getBean(
                    com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTimePolicyProvider.class)
                    .forWorkspace(unconfiguredWorkspace))
                    .isInstanceOf(TenantBusinessDatabasePolicySnapshotConflictException.class);

            CurrentAccessContext confirmedAbsentWorkspace = context(tenantValue, UUID.randomUUID(),
                    MembershipRole.SALES);
            when(settings.findDeliveryLeadTimePolicy(new TenantId(tenantValue),
                    confirmedAbsentWorkspace.workspaceId()))
                    .thenReturn(Optional.of(new OperationalSettingsAccess.DeliveryLeadTimePolicySource(
                            new TenantId(tenantValue), confirmedAbsentWorkspace.workspaceId(),
                            OperationalSettingsAccess.SourceState.CONFIRMED_ABSENT, null, 3)));
            org.assertj.core.api.Assertions.assertThat(context.getBean(
                    com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTimePolicyProvider.class)
                    .forWorkspace(confirmedAbsentWorkspace).calendarDays()).isEqualTo(3);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean OperationalSettingsAccess operationalSettingsAccess() { return mock(OperationalSettingsAccess.class); }
        @Bean PurchaseRequestPersistencePort purchaseRequestPersistencePort() { return mock(PurchaseRequestPersistencePort.class); }
        @Bean PurchaseRequestEventPersistencePort purchaseRequestEventPersistencePort() { return mock(PurchaseRequestEventPersistencePort.class); }
        @Bean IdempotencyPersistencePort idempotencyPersistencePort() { return mock(IdempotencyPersistencePort.class); }
        @Bean CatalogItemSnapshotLookupPort catalogItemSnapshotLookupPort() { return mock(CatalogItemSnapshotLookupPort.class); }
        @Bean SellableSkuSnapshotLookupPort sellableSkuSnapshotLookupPort() { return mock(SellableSkuSnapshotLookupPort.class); }
        @Bean CustomerAccountQuery customerAccountQuery() { return mock(CustomerAccountQuery.class); }
        @Bean CustomerAddressQuery customerAddressQuery() { return mock(CustomerAddressQuery.class); }
        @Bean ClientAccountCommercialPort clientAccountCommercialPort() { return mock(ClientAccountCommercialPort.class); }
        @Bean WarehouseReferencePort warehouseReferencePort() { return mock(WarehouseReferencePort.class); }
        @Bean MapRoutingPort mapRoutingPort() { return mock(MapRoutingPort.class); }
        @Bean PeruGeographyPersistencePort peruGeographyPersistencePort() { return mock(PeruGeographyPersistencePort.class); }
        @Bean ManualSalesOrderPersistencePort manualSalesOrderPersistencePort() { return mock(ManualSalesOrderPersistencePort.class); }
        @Bean ManualSalesOrderDraftPersistencePort manualSalesOrderDraftPersistencePort() { return mock(ManualSalesOrderDraftPersistencePort.class); }
        @Bean ChangeEventPersistencePort changeEventPersistencePort() { return mock(ChangeEventPersistencePort.class); }
        @Bean CommercialCommitmentPort commercialCommitmentPort() { return mock(CommercialCommitmentPort.class); }
        @Bean MaterialChangePersistencePort materialChangePersistencePort() { return mock(MaterialChangePersistencePort.class); }
        @Bean SalesOrderPersistencePort salesOrderPersistencePort() { return mock(SalesOrderPersistencePort.class); }
        @Bean SalesOrderAggregatePersistencePort salesOrderAggregatePersistencePort() { return mock(SalesOrderAggregatePersistencePort.class); }
        @Bean SalesOrderConversionPersistencePort salesOrderConversionPersistencePort() { return mock(SalesOrderConversionPersistencePort.class); }
        @Bean SellableSkuQuery sellableSkuQuery() { return mock(SellableSkuQuery.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    private static CurrentAccessContext context(UUID tenantValue, UUID workspaceValue, MembershipRole role) {
        TenantId tenant = new TenantId(tenantValue);
        WorkspaceId workspace = new WorkspaceId(workspaceValue);
        RoleDefinition definition = RoleDefinition.systemTemplate(role, Instant.EPOCH);
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(), 1);
        Membership membership = new Membership(MembershipId.random(), UserId.random(), tenant, workspace,
                Set.of(role), authorization.roleCodes(), authorization.roleDefinitionIds(),
                MembershipStatus.ACTIVE, 1);
        Surface surface = role == MembershipRole.BUYER ? Surface.PORTAL : Surface.PLATFORM;
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }
}
