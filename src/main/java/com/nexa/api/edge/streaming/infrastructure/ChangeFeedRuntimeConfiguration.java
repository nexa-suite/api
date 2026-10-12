package com.nexa.api.edge.streaming.infrastructure;

import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.edge.streaming.ChangeFeedQueryPort;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import com.nexa.api.edge.streaming.ChangeFeedStreamService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ChangeFeedRuntimeConfiguration {
	@Bean(destroyMethod = "close")
	ChangeFeedStreamService changeFeedStreamService(ChangeFeedReadUseCase feed, ResolveCurrentAccessContextUseCase accessContext,
			ValidateAccessSessionUseCase accessSession,
			@Value("${nexa.change-feed.global-limit:100}") int globalLimit,
			@Value("${nexa.change-feed.session-limit:2}") int sessionLimit,
			@Value("${nexa.change-feed.user-surface-limit:3}") int userSurfaceLimit,
			@Value("${nexa.change-feed.workspace-limit:50}") int workspaceLimit) {
		return new ChangeFeedStreamService(feed, accessContext, accessSession,
				new com.nexa.api.edge.streaming.ChangeFeedConnectionRegistry(globalLimit, sessionLimit, userSurfaceLimit, workspaceLimit));
	}

	@Bean
	ChangeFeedReadUseCase centralChangeFeedReadUseCase(ChangeFeedQueryPort feed,
			com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery accounts) {
		return new CentralChangeFeedReadUseCase(feed, accounts);
	}
}
