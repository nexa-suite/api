package com.nexa.api.iam.infrastructure;

import com.nexa.api.iam.application.port.in.CurrentSessionUseCase;
import com.nexa.api.iam.application.port.in.RefreshSessionUseCase;
import com.nexa.api.iam.application.port.in.SignInUseCase;
import com.nexa.api.iam.application.port.in.SignOutUseCase;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.iam.application.port.out.SessionPort;
import com.nexa.api.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.iam.application.service.CurrentSessionService;
import com.nexa.api.iam.application.service.RefreshSessionService;
import com.nexa.api.iam.application.service.SignInService;
import com.nexa.api.iam.application.service.SignOutService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class IamRuntimeConfiguration {
	@Bean
	Clock applicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	SignInUseCase signInUseCase(UserAccountQueryPort users, PasswordVerificationPort passwords, AccessPolicyPort policies,
			AuthenticationTokenPort tokens, SessionPort sessions, Clock clock) {
		return new SignInService(users, passwords, policies, tokens, sessions, clock);
	}

	@Bean
	RefreshSessionUseCase refreshSessionUseCase(SessionPort sessions, AccessPolicyPort policies,
			AuthenticationTokenPort tokens, Clock clock) {
		return new RefreshSessionService(sessions, policies, tokens, clock);
	}

	@Bean
	SignOutUseCase signOutUseCase(SessionPort sessions, Clock clock) {
		return new SignOutService(sessions, clock);
	}

	@Bean
	CurrentSessionUseCase currentSessionUseCase(SessionPort sessions, Clock clock) {
		return new CurrentSessionService(sessions, clock);
	}
}
