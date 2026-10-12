package com.nexa.api.edge.security;

import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiSecurityConfigurationNonWebContextTests {

	private final ApplicationContextRunner fixtureCliContext = new ApplicationContextRunner()
			.withUserConfiguration(ApiSecurityConfiguration.class, FixtureCliDependencies.class)
			.withPropertyValues("spring.profiles.active=local,local-fixtures");

	@Test
	void localFixtureNonWebContextStartsWithoutServletSecurityChains() {
		fixtureCliContext.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
			assertThat(AopUtils.isAopProxy(context.getBean(FixtureSecuredService.class))).isTrue();
		});
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixtureCliDependencies {
		@Bean
		ObjectMapper fixtureObjectMapper() {
			return new ObjectMapper();
		}

		@Bean
		InternalOperatorAuthenticationFilter internalOperatorAuthenticationFilter(ObjectMapper objectMapper) {
			return new InternalOperatorAuthenticationFilter(objectMapper, "");
		}

		@Bean
		SystemOperatorAuthenticationFilter systemOperatorAuthenticationFilter(ObjectMapper objectMapper,
				ListableBeanFactory beanFactory) {
			return new SystemOperatorAuthenticationFilter(objectMapper,
					beanFactory.getBeanProvider(com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort.class),
					beanFactory.getBeanProvider(org.springframework.jdbc.core.JdbcTemplate.class), "");
		}

		@Bean
		JwtAuthenticationConverter jwtAuthenticationConverter() {
			return new JwtAuthenticationConverter();
		}

		@Bean
		ResolveCurrentAccessContextUseCase resolveCurrentAccessContextUseCase() {
			return mock(ResolveCurrentAccessContextUseCase.class);
		}

		@Bean
		ValidateAccessSessionUseCase validateAccessSessionUseCase() {
			return mock(ValidateAccessSessionUseCase.class);
		}

		@Bean
		FixtureSecuredService fixtureSecuredService() {
			return new FixtureSecuredService();
		}
	}

	static class FixtureSecuredService {
		@PreAuthorize("isAuthenticated()")
		public void securedOperation() {
		}
	}
}
