package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.in.OrganizationAdministrationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService;
import com.nexa.api.tenantmanagement.application.service.BuyerMembershipDirectoryService;
import com.nexa.api.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationVersionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class OrganizationAdministrationRuntimeConfiguration {
	@Bean
	BuyerMembershipDirectory buyerMembershipDirectory(OrganizationAdministrationPort port) {
		return new BuyerMembershipDirectoryService(port);
	}

	@Bean
	OrganizationAdministrationUseCase organizationAdministrationUseCase(OrganizationAdministrationPort port,
			com.nexa.api.shared.application.port.out.SecurityAuditPort audit,
			com.nexa.api.shared.application.port.out.ChangeEventPersistencePort changes,
			RoleDefinitionPersistencePort roleDefinitions, AuthorizationVersionPort authorizationVersions,
			PlatformTransactionManager transactionManager) {
		return TenantTransactionalProxy.required(new OrganizationAdministrationService(port, audit, changes, roleDefinitions, authorizationVersions),
				OrganizationAdministrationUseCase.class, transactionManager);
	}
}
