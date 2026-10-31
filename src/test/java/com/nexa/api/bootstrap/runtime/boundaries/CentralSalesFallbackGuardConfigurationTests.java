package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
