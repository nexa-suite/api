package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionPort;
import com.nexa.api.tenantmanagement.application.service.ResolveCurrentAccessContextService;
import com.nexa.api.tenantmanagement.application.port.in.RoleDefinitionUseCase;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantmanagement.application.service.RoleDefinitionService;
import com.nexa.api.tenantmanagement.application.service.TenantOperationalSettingsIntegrationService;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantManagementRuntimeConfiguration {
	@Bean
	ResolveCurrentAccessContextUseCase resolveCurrentAccessContextUseCase(VerifiedMembershipResolutionPort memberships) {
		return new ResolveCurrentAccessContextService(memberships);
	}

	@Bean
	RoleDefinitionUseCase roleDefinitionUseCase(RoleDefinitionPersistencePort definitions, AuthorizationVersionPort versions,
			ChangeEventPersistencePort changes, Clock clock, PlatformTransactionManager transactionManager) {
		return TenantTransactionalProxy.required(new RoleDefinitionService(definitions, versions, changes, clock),
				RoleDefinitionUseCase.class, transactionManager);
	}

	@Bean
	TenantOperationalSettingsIntegrationService tenantOperationalSettingsIntegrationService(
			TenantConfigurationPort configuration) {
		return new TenantOperationalSettingsIntegrationService(configuration);
	}
}
