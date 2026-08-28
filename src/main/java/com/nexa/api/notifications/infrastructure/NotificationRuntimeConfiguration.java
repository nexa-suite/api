package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import com.nexa.api.notifications.application.port.out.PushNotificationOutboxPort;
import com.nexa.api.notifications.application.service.NotificationService;
import com.nexa.api.notifications.application.service.PushRoutingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class NotificationRuntimeConfiguration {
	@Bean
	NotificationUseCase notificationUseCase(NotificationInboxPersistencePort inbox,
		NotificationPreferencePersistencePort preferences, CustomerAccountQuery accounts,
				ObjectProvider<PushRoutingService> pushRouting, ApplicationEventPublisher eventPublisher,
				ObjectProvider<PushNotificationOutboxPort> pushOutbox) {
		return new NotificationService(inbox, preferences, accounts, pushRouting.getIfAvailable(), eventPublisher,
				pushOutbox.getIfAvailable());
	}

	@Bean
	NotificationProjectionPort notificationProjectionPort(NotificationUseCase service) {
		return (NotificationProjectionPort) service;
	}
}
