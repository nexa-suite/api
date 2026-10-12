package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQueryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps central support APIs composable when no Tenant sales-order router is available. */
@Configuration(proxyBeanMethods = false)
public class SupportSalesOrderReadUnavailableConfiguration {
	@Bean
	@ConditionalOnMissingBean(SupportSalesOrderReadQueryFactory.class)
	SupportSalesOrderReadQueryFactory unavailableSupportSalesOrderReadQueryFactory() {
		return (SupportOrderReadGrant grant) -> () -> {
			throw new TechnicalFailureException(
					TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
					"Tenant support order read capability is unavailable");
		};
	}
}
