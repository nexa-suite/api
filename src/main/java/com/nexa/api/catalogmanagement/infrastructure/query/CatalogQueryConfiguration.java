package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.service.CatalogQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogQueryConfiguration {
	@Bean
	CatalogQueryService catalogQueryService(CatalogItemQueryPort queryPort, CatalogAuthorizationPort authorization) {
		return new CatalogQueryService(queryPort, authorization);
	}

}
