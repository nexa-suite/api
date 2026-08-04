package com.nexa.api.catalogmanagement.infrastructure;

import com.nexa.api.catalogmanagement.application.port.in.CatalogSkuUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.application.service.CatalogSkuServiceFacade;
import com.nexa.api.catalogmanagement.infrastructure.query.CatalogTransactionalProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CatalogSkuRuntimeConfiguration {
    @Bean
    CatalogSkuUseCase catalogSkuUseCase(CatalogSkuPort port, CatalogAuthorizationPort authorization,
            Clock clock, PlatformTransactionManager transactionManager) {
        return CatalogTransactionalProxy.required(new CatalogSkuServiceFacade(port, authorization, clock),
                CatalogSkuUseCase.class, transactionManager);
    }
}
