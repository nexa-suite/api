package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPreviewPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogProductPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogTaxonomyPort;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPricingPreviewUseCase;
import com.nexa.api.catalogmanagement.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogmanagement.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogmanagement.application.service.CatalogQueryService;
import com.nexa.api.catalogmanagement.application.service.CatalogPricingService;
import com.nexa.api.catalogmanagement.application.service.CatalogPricingPreviewService;
import com.nexa.api.catalogmanagement.application.service.CatalogProductService;
import com.nexa.api.catalogmanagement.application.service.CatalogPromotionService;
import com.nexa.api.catalogmanagement.application.service.CatalogTaxonomyService;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CatalogQueryConfiguration {
	@Bean
	CatalogQueryService catalogQueryService(CatalogItemQueryPort queryPort, CatalogAuthorizationPort authorization) {
		return new CatalogQueryService(queryPort, authorization);
	}

	@Bean
	@Profile("!test")
	CatalogTaxonomyUseCase catalogTaxonomyService(CatalogTaxonomyPort port, CatalogAuthorizationPort authorization,
			PlatformTransactionManager transactionManager) {
		return CatalogTransactionalProxy.required(new CatalogTaxonomyService(port, authorization), CatalogTaxonomyUseCase.class, transactionManager);
	}

	@Bean
	@Profile("!test")
	CatalogProductUseCase catalogProductService(CatalogProductPort port, CatalogAuthorizationPort authorization,
			PlatformTransactionManager transactionManager) {
		return CatalogTransactionalProxy.required(new CatalogProductService(port, authorization), CatalogProductUseCase.class, transactionManager);
	}

	@Bean
	@Profile("!test")
	CatalogPricingUseCase catalogPricingService(CatalogPricingPort port, CatalogAuthorizationPort authorization,
			PlatformTransactionManager transactionManager) {
		return CatalogTransactionalProxy.required(new CatalogPricingService(port, authorization), CatalogPricingUseCase.class, transactionManager);
	}

	@Bean
	@Profile("!test")
	CatalogPricingPreviewUseCase catalogPricingPreviewService(CatalogPricingPreviewPort port, CatalogAuthorizationPort authorization,
			Clock clock, PlatformTransactionManager transactionManager) {
		return CatalogTransactionalProxy.required(new CatalogPricingPreviewService(port, authorization, clock), CatalogPricingPreviewUseCase.class, transactionManager);
	}

	@Bean
	@Profile("!test")
	CatalogPromotionUseCase catalogPromotionService(CatalogPromotionPort port, CatalogAuthorizationPort authorization,
			PlatformTransactionManager transactionManager) {
		return CatalogTransactionalProxy.required(new CatalogPromotionService(port, authorization), CatalogPromotionUseCase.class, transactionManager);
	}

}
