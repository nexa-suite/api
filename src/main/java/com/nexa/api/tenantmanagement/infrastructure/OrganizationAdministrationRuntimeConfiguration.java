package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.in.OrganizationAdministrationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class OrganizationAdministrationRuntimeConfiguration {
	@Bean
	OrganizationAdministrationUseCase organizationAdministrationUseCase(OrganizationAdministrationPort port,
			com.nexa.api.shared.application.port.out.SecurityAuditPort audit,
			PlatformTransactionManager transactionManager) {
		return TenantTransactionalProxy.required(new OrganizationAdministrationService(port, audit),
				OrganizationAdministrationUseCase.class, transactionManager);
	}
}
