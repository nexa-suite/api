package com.nexa.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Non-web entry point used only by the explicit local Tenant fixture script. */
public final class LocalTenantBusinessFixtureCli {
	private LocalTenantBusinessFixtureCli() { }

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(NexaApiApplication.class);
		application.setAdditionalProfiles("local", "local-fixtures");
		application.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = application.run(args);
		System.exit(SpringApplication.exit(context));
	}
}
