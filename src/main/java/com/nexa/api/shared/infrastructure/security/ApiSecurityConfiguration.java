package com.nexa.api.shared.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {
	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, Environment environment,
			AuthenticationEntryPoint authenticationEntryPoint, AccessDeniedHandler accessDeniedHandler) throws Exception {
		boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));
		http.csrf(AbstractHttpConfigurer::disable)
				.cors(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> {
					authorize.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
					if (localProfile) authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
					authorize.requestMatchers("/api/**").denyAll();
					authorize.anyRequest().denyAll();
				});
		return http.build();
	}

	@Bean
	ProblemDetailAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
		return new ProblemDetailAuthenticationEntryPoint(objectMapper);
	}

	@Bean
	ProblemDetailAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return new ProblemDetailAccessDeniedHandler(objectMapper);
	}
}
