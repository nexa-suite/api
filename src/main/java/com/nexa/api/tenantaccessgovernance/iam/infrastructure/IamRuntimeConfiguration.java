package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.tenantaccessgovernance.iam.application.port.in.CurrentSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.IdentitySignInUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ListAccessContextsUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.RefreshSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SelectAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignInUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignOutUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextTicketPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextDiscoveryScopePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.service.AccessContextService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.CredentialAuthenticationService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.CurrentSessionService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.RefreshSessionService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.SignInService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.SignOutService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.ValidateAccessSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class IamRuntimeConfiguration {
	@Bean
	Clock applicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	CredentialAuthenticationService credentialAuthenticationService(UserAccountQueryPort users,
			PasswordVerificationPort passwords,
			com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationThrottlePort throttle,
			SecurityAuditPort audit) {
		return new CredentialAuthenticationService(users, passwords, throttle, audit);
	}

	@Bean
	SignInUseCase signInUseCase(CredentialAuthenticationService credentials, AccessPolicyPort policies,
			AuthenticationTokenPort tokens, SessionPort sessions, SecurityAuditPort audit, Clock clock) {
		return new SignInService(credentials, policies, tokens, sessions, audit, clock);
	}

	@Bean
	AccessContextService accessContextService(CredentialAuthenticationService credentials, UserAccountQueryPort users,
			AccessPolicyPort policies, AuthenticationTokenPort tokens, SessionPort sessions,
			AccessContextTicketPersistencePort tickets, AccessContextDiscoveryScopePort discoveryScope,
			OpaqueSecurityTokenPort opaqueTokens,
			SecurityAuditPort audit, Clock clock) {
		return new AccessContextService(credentials, users, policies, tokens, sessions, tickets,
				discoveryScope, opaqueTokens, audit, clock);
	}

	@Bean
	IdentitySignInUseCase identitySignInUseCase(AccessContextService accessContexts,
			PlatformTransactionManager transactionManager) {
		IdentitySignInUseCase target = accessContexts::identitySignIn;
		return IamTransactionalProxy.required(target, IdentitySignInUseCase.class, transactionManager);
	}

	@Bean
	ListAccessContextsUseCase listAccessContextsUseCase(AccessContextService accessContexts,
			PlatformTransactionManager transactionManager) {
		ListAccessContextsUseCase target = accessContexts::listAccessContexts;
		return IamTransactionalProxy.required(target, ListAccessContextsUseCase.class, transactionManager);
	}

	@Bean
	SelectAccessContextUseCase selectAccessContextUseCase(AccessContextService accessContexts,
			PlatformTransactionManager transactionManager) {
		SelectAccessContextUseCase target = accessContexts::selectAccessContext;
		return IamTransactionalProxy.required(target, SelectAccessContextUseCase.class, transactionManager);
	}

	@Bean
	RefreshSessionUseCase refreshSessionUseCase(SessionPort sessions, AccessPolicyPort policies,
			AuthenticationTokenPort tokens, SecurityAuditPort audit, Clock clock) {
		return new RefreshSessionService(sessions, policies, tokens, audit, clock);
	}

	@Bean
	SignOutUseCase signOutUseCase(SessionPort sessions, SecurityAuditPort audit, Clock clock) {
		return new SignOutService(sessions, audit, clock);
	}

	@Bean
	CurrentSessionUseCase currentSessionUseCase(SessionPort sessions, Clock clock) {
		return new CurrentSessionService(sessions, clock);
	}

	@Bean
	ValidateAccessSessionUseCase validateAccessSessionUseCase(SessionPort sessions, Clock clock) {
		return new ValidateAccessSessionService(sessions, clock);
	}
}
