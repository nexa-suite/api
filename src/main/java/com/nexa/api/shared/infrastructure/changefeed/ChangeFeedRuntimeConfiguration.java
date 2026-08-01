package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.shared.application.changefeed.ChangeFeedQueryPort;
import com.nexa.api.shared.application.changefeed.ChangeFeedStreamService;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ChangeFeedRuntimeConfiguration {
	@Bean(destroyMethod = "close")
	ChangeFeedStreamService changeFeedStreamService(ChangeFeedQueryPort feed, ResolveCurrentAccessContextUseCase accessContext,
			ValidateAccessSessionUseCase accessSession, ClientAccountPersistencePort accounts,
			@Value("${nexa.change-feed.global-limit:100}") int globalLimit,
			@Value("${nexa.change-feed.session-limit:2}") int sessionLimit,
			@Value("${nexa.change-feed.user-surface-limit:3}") int userSurfaceLimit,
			@Value("${nexa.change-feed.workspace-limit:50}") int workspaceLimit) {
		return new ChangeFeedStreamService(feed, accessContext, accessSession, accounts,
				new com.nexa.api.shared.application.changefeed.ChangeFeedConnectionRegistry(globalLimit, sessionLimit, userSurfaceLimit, workspaceLimit));
	}
}
