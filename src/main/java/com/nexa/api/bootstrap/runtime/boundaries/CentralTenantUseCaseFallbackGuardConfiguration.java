package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentUseCase;
import com.nexa.api.businesstraceability.application.port.in.AuditViewerUseCase;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Guards central-backed tenant business use cases while their Tenant database routes are not selected. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class CentralTenantUseCaseFallbackGuardConfiguration {
    private static final String CUSTOMER_RELATIONSHIPS_ROUTE = "nexa.tenant-business.purchase-request.enabled";
    private static final String BUSINESS_DOCUMENTS_ROUTE = "nexa.tenant-business.business-documents.enabled";
    private static final String BUSINESS_TRACEABILITY_ROUTE = "nexa.tenant-business.business-traceability.enabled";

    @Bean
    @Primary
    @Conditional(CentralCustomerRelationshipsRouteNotSelected.class)
    ClientAccountUseCase centralFallbackClientAccountUseCase(
            @Qualifier("clientAccountUseCase") ClientAccountUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(ClientAccountUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralCustomerRelationshipsRouteNotSelected.class)
    ClientAccountAddressUseCase centralFallbackClientAccountAddressUseCase(
            @Qualifier("clientAccountAddressUseCase") ClientAccountAddressUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(ClientAccountAddressUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralCustomerRelationshipsRouteNotSelected.class)
    FieldVisitUseCase centralFallbackFieldVisitUseCase(
            @Qualifier("fieldVisitService") FieldVisitUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(FieldVisitUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralCustomerRelationshipsRouteNotSelected.class)
    CreditExposureUseCase centralFallbackCreditExposureUseCase(
            @Qualifier("creditExposureApplicationService") CreditExposureUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(CreditExposureUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralCustomerRelationshipsRouteNotSelected.class)
    FinancialAdjustmentUseCase centralFallbackFinancialAdjustmentUseCase(
            @Qualifier("financialAdjustmentApplicationService") FinancialAdjustmentUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(FinancialAdjustmentUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralBusinessDocumentsRouteNotSelected.class)
    BusinessDocumentUseCase centralFallbackBusinessDocumentUseCase(
            @Qualifier("businessDocumentService") BusinessDocumentUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(BusinessDocumentUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralBusinessTraceabilityRouteNotSelected.class)
    AuditViewerUseCase centralFallbackAuditViewerUseCase(
            @Qualifier("auditViewerUseCase") AuditViewerUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(AuditViewerUseCase.class, delegate, guard);
    }

    @Bean
    @Primary
    @Conditional(CentralBusinessTraceabilityRouteNotSelected.class)
    ChangeFeedReadUseCase centralFallbackChangeFeedReadUseCase(
            @Qualifier("centralChangeFeedReadUseCase") ChangeFeedReadUseCase delegate,
            CentralTenantBusinessDataAccessGuard guard) {
        return CentralFallbackUseCaseGuard.protect(ChangeFeedReadUseCase.class, delegate, guard);
    }

    private static boolean tenantRouteSelected(ConditionContext context, String property) {
        return context.getEnvironment().acceptsProfiles(Profiles.of("local"))
                && context.getEnvironment().getProperty(property, Boolean.class, false);
    }

    static final class CentralCustomerRelationshipsRouteNotSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !tenantRouteSelected(context, CUSTOMER_RELATIONSHIPS_ROUTE);
        }
    }

    static final class CentralBusinessDocumentsRouteNotSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            // BusinessDocumentService itself is disabled when this opt-in property is true.
            return !tenantRouteSelected(context, BUSINESS_DOCUMENTS_ROUTE)
                    && !context.getEnvironment().getProperty(BUSINESS_DOCUMENTS_ROUTE, Boolean.class, false);
        }
    }

    static final class CentralBusinessTraceabilityRouteNotSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !tenantRouteSelected(context, BUSINESS_TRACEABILITY_ROUTE);
        }
    }
}
