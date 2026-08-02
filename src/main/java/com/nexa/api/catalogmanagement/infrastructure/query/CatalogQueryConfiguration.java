package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogProductPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogTaxonomyPort;
import com.nexa.api.catalogmanagement.application.service.CatalogQueryService;
import com.nexa.api.catalogmanagement.application.service.CatalogPricingService;
import com.nexa.api.catalogmanagement.application.service.CatalogProductService;
import com.nexa.api.catalogmanagement.application.service.CatalogPromotionService;
import com.nexa.api.catalogmanagement.application.service.CatalogTaxonomyService;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogQueryConfiguration {
	@Bean
	CatalogQueryService catalogQueryService(CatalogItemQueryPort queryPort, CatalogAuthorizationPort authorization) {
		return new CatalogQueryService(queryPort, authorization);
	}

	@Bean
	@Profile("!test")
	CatalogTaxonomyService catalogTaxonomyService(CatalogTaxonomyPort port, CatalogAuthorizationPort authorization) {
		return new CatalogTaxonomyService(port, authorization);
	}

	@Bean
	@Profile("!test")
	CatalogProductService catalogProductService(CatalogProductPort port, CatalogAuthorizationPort authorization) {
		return new CatalogProductService(port, authorization);
	}

	@Bean
	@Profile("!test")
	CatalogPricingService catalogPricingService(CatalogPricingPort port, CatalogAuthorizationPort authorization) {
		return new CatalogPricingService(port, authorization);
	}

	@Bean
	@Profile("!test")
	CatalogPromotionService catalogPromotionService(CatalogPromotionPort port, CatalogAuthorizationPort authorization) {
		return new CatalogPromotionService(port, authorization);
	}

}
