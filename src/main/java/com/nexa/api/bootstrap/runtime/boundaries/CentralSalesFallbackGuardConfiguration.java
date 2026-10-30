package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Profiles;

/** Keeps every central-backed Sales entrypoint off a Tenant once any database binding exists. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@Conditional(CentralSalesFallbackGuardConfiguration.CentralSalesRouteSelected.class)
class CentralSalesFallbackGuardConfiguration {
    @Bean
    @Primary
    PurchaseRequestDraftPort centralFallbackPurchaseRequestDraftPort(
            @Qualifier("purchaseRequestDraftService") PurchaseRequestDraftPort delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(PurchaseRequestDraftPort.class, delegate, guard);
    }

    @Bean
    @Primary
    PurchaseRequestUseCase centralFallbackPurchaseRequestUseCase(
            @Qualifier("purchaseRequestUseCase") PurchaseRequestUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(PurchaseRequestUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    FieldPurchaseRequestSubmissionUseCase centralFallbackFieldPurchaseRequestSubmissionUseCase(
            @Qualifier("fieldPurchaseRequestSubmissionService") FieldPurchaseRequestSubmissionUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(FieldPurchaseRequestSubmissionUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    SalesOrderUseCase centralFallbackSalesOrderUseCase(
            @Qualifier("salesOrderUseCase") SalesOrderUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(SalesOrderUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    DirectOrderUseCase centralFallbackDirectOrderUseCase(
            @Qualifier("directOrderUseCase") DirectOrderUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(DirectOrderUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    ManualSalesOrderUseCase centralFallbackManualSalesOrderUseCase(
            @Qualifier("manualSalesOrderUseCase") ManualSalesOrderUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(ManualSalesOrderUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    ManualSalesOrderDraftUseCase centralFallbackManualSalesOrderDraftUseCase(
            @Qualifier("manualSalesOrderDraftUseCase") ManualSalesOrderDraftUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(ManualSalesOrderDraftUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    SalesOrderSummaryExportUseCase centralFallbackSalesOrderSummaryExportUseCase(
            @Qualifier("salesOrderSummaryExportUseCase") SalesOrderSummaryExportUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(SalesOrderSummaryExportUseCase.class, delegate, guard);
    }

    static final class CentralSalesRouteSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean localRouteEnabled = context.getEnvironment().acceptsProfiles(Profiles.of("local"))
                    && context.getEnvironment().getProperty(
                            "nexa.tenant-business.purchase-request.enabled", Boolean.class, false);
            return !localRouteEnabled;
        }
    }
}
