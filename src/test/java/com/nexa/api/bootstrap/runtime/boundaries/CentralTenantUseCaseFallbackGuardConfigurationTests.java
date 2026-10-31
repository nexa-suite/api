package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentUseCase;
import com.nexa.api.businesstraceability.application.port.in.AuditViewerUseCase;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CentralTenantUseCaseFallbackGuardConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CentralTenantUseCaseFallbackGuardConfiguration.class,
                    CentralTenantBusinessDataAccessGuardConfiguration.class, CentralDelegates.class);

    @Test
    void selectedCentralRoutesRejectTenantScopedCallsBeforeAnyCentralDelegateRuns() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            assertBlocked(context.getBean(ClientAccountUseCase.class),
                    context.getBean("clientAccountUseCase", ClientAccountUseCase.class));
            assertBlocked(context.getBean(ClientAccountAddressUseCase.class),
                    context.getBean("clientAccountAddressUseCase", ClientAccountAddressUseCase.class));
            assertBlocked(context.getBean(FieldVisitUseCase.class),
                    context.getBean("fieldVisitService", FieldVisitUseCase.class));
            assertBlocked(context.getBean(CreditExposureUseCase.class),
                    context.getBean("creditExposureApplicationService", CreditExposureUseCase.class));
            assertBlocked(context.getBean(FinancialAdjustmentUseCase.class),
                    context.getBean("financialAdjustmentApplicationService", FinancialAdjustmentUseCase.class));
            assertBlocked(context.getBean(BusinessDocumentUseCase.class),
                    context.getBean("businessDocumentService", BusinessDocumentUseCase.class));
            assertBlocked(context.getBean(AuditViewerUseCase.class),
                    context.getBean("auditViewerUseCase", AuditViewerUseCase.class));
            assertBlocked(context.getBean(ChangeFeedReadUseCase.class),
                    context.getBean("centralChangeFeedReadUseCase", ChangeFeedReadUseCase.class));

            verifyNoInteractions(context.getBean("clientAccountUseCase"));
            verifyNoInteractions(context.getBean("clientAccountAddressUseCase"));
            verifyNoInteractions(context.getBean("fieldVisitService"));
            verifyNoInteractions(context.getBean("creditExposureApplicationService"));
            verifyNoInteractions(context.getBean("financialAdjustmentApplicationService"));
            verifyNoInteractions(context.getBean("businessDocumentService"));
            verifyNoInteractions(context.getBean("auditViewerUseCase"));
            verifyNoInteractions(context.getBean("centralChangeFeedReadUseCase"));
        });
    }

    @Test
    void localTenantRoutesRemainThePrimaryUseCasesWhenTheirFlagsAreEnabled() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local",
                        "nexa.tenant-business.purchase-request.enabled=true",
                        "nexa.tenant-business.business-documents.enabled=true",
                        "nexa.tenant-business.business-traceability.enabled=true")
                .withUserConfiguration(LocalTenantRoutes.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ClientAccountUseCase.class))
                            .isSameAs(context.getBean("localClientAccountUseCase"));
                    assertThat(context.getBean(ClientAccountAddressUseCase.class))
                            .isSameAs(context.getBean("localClientAccountAddressUseCase"));
                    assertThat(context.getBean(FieldVisitUseCase.class))
                            .isSameAs(context.getBean("localFieldVisitUseCase"));
                    assertThat(context.getBean(CreditExposureUseCase.class))
                            .isSameAs(context.getBean("localCreditExposureUseCase"));
                    assertThat(context.getBean(FinancialAdjustmentUseCase.class))
                            .isSameAs(context.getBean("localFinancialAdjustmentUseCase"));
                    assertThat(context.getBean(BusinessDocumentUseCase.class))
                            .isSameAs(context.getBean("localBusinessDocumentUseCase"));
                    assertThat(context.getBean(AuditViewerUseCase.class))
                            .isSameAs(context.getBean("localAuditViewerUseCase"));
                    assertThat(context.getBean(ChangeFeedReadUseCase.class))
                            .isSameAs(context.getBean("localChangeFeedReadUseCase"));

                    assertThat(context.containsBean("centralFallbackClientAccountUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackClientAccountAddressUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackFieldVisitUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackCreditExposureUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackFinancialAdjustmentUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackBusinessDocumentUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackAuditViewerUseCase")).isFalse();
                    assertThat(context.containsBean("centralFallbackChangeFeedReadUseCase")).isFalse();
                });
    }

    private static void assertBlocked(Object guarded, Object delegate) {
        Method entrypoint = Arrays.stream(guarded.getClass().getInterfaces())
                .flatMap(contract -> Arrays.stream(contract.getMethods()))
                .filter(method -> Arrays.asList(method.getParameterTypes()).contains(CurrentAccessContext.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Tenant-context entrypoint found"));
        Object[] arguments = new Object[entrypoint.getParameterCount()];
        for (int index = 0; index < arguments.length; index++) {
            Class<?> parameter = entrypoint.getParameterTypes()[index];
            if (parameter == CurrentAccessContext.class) arguments[index] = mock(CurrentAccessContext.class);
            else if (parameter == int.class) arguments[index] = 0;
            else if (parameter == long.class) arguments[index] = 0L;
            else if (parameter == boolean.class) arguments[index] = false;
        }

        assertThatThrownBy(() -> {
            try {
                entrypoint.invoke(guarded, arguments);
            } catch (InvocationTargetException exception) {
                throw (RuntimeException) exception.getCause();
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }).isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
        assertThat(guarded).isNotSameAs(delegate);
    }

    @Configuration(proxyBeanMethods = false)
    static class CentralDelegates {
        @Bean(name = "clientAccountUseCase")
        ClientAccountUseCase clientAccountUseCase() { return mock(ClientAccountUseCase.class); }

        @Bean(name = "clientAccountAddressUseCase")
        ClientAccountAddressUseCase clientAccountAddressUseCase() { return mock(ClientAccountAddressUseCase.class); }

        @Bean(name = "fieldVisitService")
        FieldVisitUseCase fieldVisitService() { return mock(FieldVisitUseCase.class); }

        @Bean(name = "creditExposureApplicationService")
        CreditExposureUseCase creditExposureApplicationService() { return mock(CreditExposureUseCase.class); }

        @Bean(name = "financialAdjustmentApplicationService")
        FinancialAdjustmentUseCase financialAdjustmentApplicationService() {
            return mock(FinancialAdjustmentUseCase.class);
        }

        @Bean(name = "businessDocumentService")
        BusinessDocumentUseCase businessDocumentService() { return mock(BusinessDocumentUseCase.class); }

        @Bean(name = "auditViewerUseCase")
        AuditViewerUseCase auditViewerUseCase() { return mock(AuditViewerUseCase.class); }

        @Bean(name = "centralChangeFeedReadUseCase")
        ChangeFeedReadUseCase centralChangeFeedReadUseCase() { return mock(ChangeFeedReadUseCase.class); }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalTenantRoutes {
        @Bean(name = "localClientAccountUseCase")
        @Primary
        ClientAccountUseCase clientAccountUseCase() { return mock(ClientAccountUseCase.class); }

        @Bean(name = "localClientAccountAddressUseCase")
        @Primary
        ClientAccountAddressUseCase clientAccountAddressUseCase() {
            return mock(ClientAccountAddressUseCase.class);
        }

        @Bean(name = "localFieldVisitUseCase")
        @Primary
        FieldVisitUseCase fieldVisitUseCase() { return mock(FieldVisitUseCase.class); }

        @Bean(name = "localCreditExposureUseCase")
        @Primary
        CreditExposureUseCase creditExposureUseCase() { return mock(CreditExposureUseCase.class); }

        @Bean(name = "localFinancialAdjustmentUseCase")
        @Primary
        FinancialAdjustmentUseCase financialAdjustmentUseCase() {
            return mock(FinancialAdjustmentUseCase.class);
        }

        @Bean(name = "localBusinessDocumentUseCase")
        @Primary
        BusinessDocumentUseCase businessDocumentUseCase() { return mock(BusinessDocumentUseCase.class); }

        @Bean(name = "localAuditViewerUseCase")
        @Primary
        AuditViewerUseCase auditViewerUseCase() { return mock(AuditViewerUseCase.class); }

        @Bean(name = "localChangeFeedReadUseCase")
        @Primary
        ChangeFeedReadUseCase changeFeedReadUseCase() { return mock(ChangeFeedReadUseCase.class); }
    }
}
