package com.nexa.api.notifications.infrastructure;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationRuntimeConfigurationTests {
	@Test
	void exposesOneBeanForEachNotificationContract() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.register(NotificationRuntimeConfiguration.class);
			context.registerBean(NotificationInboxPersistencePort.class,
					() -> mock(NotificationInboxPersistencePort.class));
			context.registerBean(NotificationPreferencePersistencePort.class,
					() -> mock(NotificationPreferencePersistencePort.class));
			context.registerBean(CustomerAccountQuery.class,
					() -> mock(CustomerAccountQuery.class));
			context.refresh();

			assertThat(context.getBeanNamesForType(NotificationUseCase.class))
					.containsExactly("notificationUseCase");
			assertThat(context.getBeanNamesForType(NotificationProjectionPort.class))
					.containsExactly("notificationProjectionPort");
		}
	}
}
