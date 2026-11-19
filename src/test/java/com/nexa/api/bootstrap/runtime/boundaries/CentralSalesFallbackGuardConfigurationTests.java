package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CentralSalesFallbackGuardConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CentralSalesFallbackGuardConfiguration.class,
                    CentralTenantBusinessDataAccessGuardConfiguration.class, CentralSalesDelegates.class);

    @Test
    void fieldSubmissionIsGuardedBeforeTheCentralServiceCanReadAccountOrCatalogData() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            FieldPurchaseRequestSubmissionUseCase guarded = context.getBean(FieldPurchaseRequestSubmissionUseCase.class);
            FieldPurchaseRequestSubmissionUseCase central = context.getBean(
                    "fieldPurchaseRequestSubmissionService", FieldPurchaseRequestSubmissionUseCase.class);

            assertThat(guarded).isNotSameAs(central);
            assertThatThrownBy(() -> guarded.submit(mock(CurrentAccessContext.class), null, "retry-key"))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(central);
        });
    }

    @Test
    void cloudOptInRoutesOnlyBuyerExportsThroughReadOnlyTenantTransactions() {
        var buyer = mock(CurrentAccessContext.class);
        when(buyer.surface()).thenReturn(Surface.PORTAL);
        when(buyer.hasRole(MembershipRole.BUYER)).thenReturn(true);
        var workforce = mock(CurrentAccessContext.class);
        when(workforce.surface()).thenReturn(Surface.PLATFORM);
        var format = SalesOrderSummaryExportFormat.PDF;
        var result = new SalesOrderSummaryExportResult("summary.pdf", format.contentType(), new byte[]{1});
        var cloudRunner = contextRunner.withUserConfiguration(TenantBoundSalesOrderSummaryExportConfiguration.class)
                .withPropertyValues("spring.profiles.active=tenant-cloud")
                .withBean(TenantBusinessDatabaseRouter.class, () -> mock(TenantBusinessDatabaseRouter.class))
                .withBean(TenantCustomerAccountQueryFactory.class, () -> mock(TenantCustomerAccountQueryFactory.class))
                .withBean(TenantSalesOrderSummaryExportFactory.class, () -> mock(TenantSalesOrderSummaryExportFactory.class));

        cloudRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(SalesOrderSummaryExportUseCase.class)
                    .exportBuyer(buyer, "order-id", format))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(context.getBean(TenantBusinessDatabaseRouter.class),
                    context.getBean("salesOrderSummaryExportUseCase"));
        });

        cloudRunner.withPropertyValues("nexa.tenant-business.cloud-buyer-journey-read.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var router = context.getBean(TenantBusinessDatabaseRouter.class);
            when(router.<SalesOrderSummaryExportResult>inReadOnlyTransaction(eq(buyer), any())).thenReturn(result);
            var exports = context.getBean(SalesOrderSummaryExportUseCase.class);
            assertThat(exports.exportBuyer(buyer, "order-id", format)).isSameAs(result);
            assertThatThrownBy(() -> exports.export(buyer, "order-id", format))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            assertThatThrownBy(() -> exports.exportBuyer(workforce, "order-id", format))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verify(router).inReadOnlyTransaction(eq(buyer), any());
            verifyNoMoreInteractions(router);
            verifyNoInteractions(context.getBean("salesOrderSummaryExportUseCase"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CentralSalesDelegates {
        @Bean(name = "purchaseRequestDraftService")
        PurchaseRequestDraftPort purchaseRequestDraftService() {
            return mock(PurchaseRequestDraftPort.class);
        }

        @Bean(name = "purchaseRequestUseCase")
        PurchaseRequestUseCase purchaseRequestUseCase() {
            return mock(PurchaseRequestUseCase.class);
        }

        @Bean(name = "fieldPurchaseRequestSubmissionService")
        FieldPurchaseRequestSubmissionUseCase fieldPurchaseRequestSubmissionService() {
            return mock(FieldPurchaseRequestSubmissionUseCase.class);
        }

        @Bean(name = "salesOrderUseCase")
        SalesOrderUseCase salesOrderUseCase() {
            return mock(SalesOrderUseCase.class);
        }

        @Bean(name = "directOrderUseCase")
        DirectOrderUseCase directOrderUseCase() {
            return mock(DirectOrderUseCase.class);
        }

        @Bean(name = "manualSalesOrderUseCase")
        ManualSalesOrderUseCase manualSalesOrderUseCase() {
            return mock(ManualSalesOrderUseCase.class);
        }

        @Bean(name = "manualSalesOrderDraftUseCase")
        ManualSalesOrderDraftUseCase manualSalesOrderDraftUseCase() {
            return mock(ManualSalesOrderDraftUseCase.class);
        }

        @Bean(name = "salesOrderSummaryExportUseCase")
        SalesOrderSummaryExportUseCase salesOrderSummaryExportUseCase() {
            return mock(SalesOrderSummaryExportUseCase.class);
        }
    }
}
