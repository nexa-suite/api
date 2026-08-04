package com.nexa.api.catalogmanagement.infrastructure;

import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.application.service.CatalogSkuServiceFacade;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CatalogSkuRuntimeConfiguration {
    @Bean
    CatalogSkuServiceFacade catalogSkuServiceFacade(CatalogSkuPort port) {
        return new CatalogSkuServiceFacade(port);
    }
}
