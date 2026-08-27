package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.TenantConfigurationUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.InvitationUseCase;
import com.nexa.api.shared.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.InvitationPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.OrganizationInvitationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.TenantConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantConfigurationRuntimeConfiguration {
	@Bean
	TenantConfigurationUseCase tenantConfigurationUseCase(TenantConfigurationPort configuration,
			OrganizationAdministrationPort scope, SecurityAuditPort audit, Clock clock,
			PlatformTransactionManager transactionManager) {
		return TenantTransactionalProxy.required(new TenantConfigurationService(configuration, scope, audit, clock),
				TenantConfigurationUseCase.class, transactionManager);
	}

	@Bean
	InvitationUseCase invitationUseCase(InvitationPersistencePort invitations, TenantConfigurationPort configuration,
			com.nexa.api.shared.application.port.out.OpaqueSecurityTokenPort tokens,
			com.nexa.api.shared.application.port.out.PasswordHashPort hasher,
			com.nexa.api.shared.application.port.out.SecurityNotificationOutboxPort outbox,
			SecurityAuditPort audit, Clock clock,
				PasswordVerificationPort passwordVerifier,
			PlatformTransactionManager transactionManager) {
		return TenantTransactionalProxy.required(new OrganizationInvitationService(invitations, configuration, tokens, hasher, outbox, audit, clock, passwordVerifier),
				InvitationUseCase.class, transactionManager);
	}
}
