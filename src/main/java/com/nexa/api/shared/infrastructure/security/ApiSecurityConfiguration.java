package com.nexa.api.shared.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ApiSecurityConfiguration {
	@Bean
	@Order(-100)
	SecurityFilterChain systemOperatorSecurityFilterChain(HttpSecurity http, SystemOperatorAuthenticationFilter operatorFilter,
			AuthenticationEntryPoint authenticationEntryPoint, AccessDeniedHandler accessDeniedHandler) throws Exception {
		http.securityMatcher("/api/v1/internal/organization-registrations/*/activation",
				"/api/v1/internal/organization-registrations/*/rejection")
				.csrf(AbstractHttpConfigurer::disable)
				.cors(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
				.addFilterBefore(operatorFilter, BearerTokenAuthenticationFilter.class)
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, Environment environment,
			AuthenticationEntryPoint authenticationEntryPoint, AccessDeniedHandler accessDeniedHandler,
			ObjectMapper objectMapper, JwtAuthenticationConverter jwtAuthenticationConverter,
			ObjectProvider<CurrentAccessContextFilter> currentAccessContextFilter) throws Exception {
				boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));
				boolean observabilityProfile = environment.acceptsProfiles(Profiles.of("observability"));
		Set<String> allowedOrigins = allowedOrigins(environment);
			http.csrf(AbstractHttpConfigurer::disable)
				.headers(headers -> {
					headers.contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"));
					headers.contentTypeOptions(content -> { });
					headers.referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
					headers.permissionsPolicy(permissions -> permissions.policy("camera=(), microphone=(), geolocation=()"));
					if (!localProfile) headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).preload(true));
				})
				.cors(cors -> cors.configurationSource(corsConfigurationSource(environment)))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new CookieOriginGuardFilter(objectMapper, allowedOrigins), BearerTokenAuthenticationFilter.class)
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(authenticationEntryPoint))
				.authorizeHttpRequests(authorize -> {
				authorize.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
					if (observabilityProfile) {
						if (localProfile) authorize.requestMatchers("/actuator/metrics/**", "/actuator/prometheus").permitAll();
						else authorize.requestMatchers("/actuator/metrics/**", "/actuator/prometheus").authenticated();
					}
					if (localProfile) authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
						authorize.requestMatchers("/api/v1/authentication/sign-in", "/api/v1/authentication/refresh",
						"/api/v1/authentication/sign-out", "/api/v1/auth/workspace-previews",
						"/api/v1/integrations/stripe/webhooks",
						"/api/v1/auth/password-reset-requests", "/api/v1/auth/password-resets",
						"/api/v1/organization-invitation-acceptances",
						"/api/v1/public/contact-requests",
						"/api/v1/tenant-management/organization-registrations",
						"/api/v1/tenant-management/organization-registrations/**").permitAll();
					authorize.requestMatchers("/api/**").authenticated();
					authorize.anyRequest().denyAll();
					});
		CurrentAccessContextFilter accessFilter = currentAccessContextFilter.getIfAvailable();
		if (accessFilter != null) http.addFilterAfter(accessFilter, BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(Environment environment) {
		var configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.copyOf(allowedOrigins(environment)));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Correlation-Id", "X-Trace-ID", "X-Nexa-Surface"));
		configuration.setExposedHeaders(List.of("ETag", "X-Correlation-ID", "X-Trace-ID"));
		configuration.setAllowCredentials(true);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	private static Set<String> allowedOrigins(Environment environment) {
		return Arrays.stream(environment.getProperty("nexa.security.allowed-origins", "http://localhost:4200,http://localhost:4300,http://localhost:8000,http://127.0.0.1:4200,http://127.0.0.1:4300,http://127.0.0.1:8000").split(","))
				.map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Bean
	ProblemDetailAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
		return new ProblemDetailAuthenticationEntryPoint(objectMapper);
	}

	@Bean
	ProblemDetailAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper,
			org.springframework.beans.factory.ObjectProvider<com.nexa.api.shared.application.port.out.SecurityAuditPort> audit,
			org.springframework.beans.factory.ObjectProvider<com.nexa.api.shared.infrastructure.observability.SecurityMetrics> metrics) {
		return new ProblemDetailAccessDeniedHandler(objectMapper, audit, metrics);
	}

	@Bean
	@org.springframework.context.annotation.Profile("!test")
	CurrentAccessContextFilter currentAccessContextFilter(
			com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase accessContext,
			com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase accessSession,
			AuthenticationEntryPoint authenticationEntryPoint, AccessTokenInvalidEntryPoint accessTokenInvalidEntryPoint,
			AccessContextInvalidHandler accessContextInvalidHandler) {
		return new CurrentAccessContextFilter(accessContext, accessSession, authenticationEntryPoint,
				accessTokenInvalidEntryPoint, accessContextInvalidHandler);
	}

	@Bean
	AccessContextInvalidHandler accessContextInvalidHandler(ObjectMapper objectMapper,
			org.springframework.beans.factory.ObjectProvider<com.nexa.api.shared.application.port.out.SecurityAuditPort> audit,
			org.springframework.beans.factory.ObjectProvider<com.nexa.api.shared.infrastructure.observability.SecurityMetrics> metrics) {
		return new AccessContextInvalidHandler(objectMapper, audit, metrics);
	}

	@Bean
	AccessTokenInvalidEntryPoint accessTokenInvalidEntryPoint(ObjectMapper objectMapper) {
		return new AccessTokenInvalidEntryPoint(objectMapper);
	}
}
