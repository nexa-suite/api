package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantmanagement.application.service.ResolveCurrentAccessContextService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantManagementRuntimeConfiguration {
	@Bean
	ResolveCurrentAccessContextUseCase resolveCurrentAccessContextUseCase(VerifiedMembershipResolutionPort memberships) {
		return new ResolveCurrentAccessContextService(memberships);
	}
}
