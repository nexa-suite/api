package com.nexa.api.bootstrap;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class FlywayRuntimeConfiguration {
	@Bean
	Flyway flyway(DataSource dataSource, Environment environment) {
		return Flyway.configure().dataSource(dataSource)
				.locations(environment.getProperty("spring.flyway.locations", "classpath:db/migration"))
				.load();
	}

	@Bean(name = "flywayMigrationRunner")
	Object flywayMigrationRunner(Flyway flyway, Environment environment) {
		if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) flyway.migrate();
		return new Object();
	}

	@Bean
	static BeanFactoryPostProcessor flywayBeforeJpa() {
		return beanFactory -> {
			if (beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory configurable
					&& configurable.containsBeanDefinition("entityManagerFactory")) {
				BeanDefinition entityManager = configurable.getBeanDefinition("entityManagerFactory");
				String[] currentDependencies = entityManager.getDependsOn();
				String[] dependencies = new String[(currentDependencies == null ? 0 : currentDependencies.length) + 1];
				if (currentDependencies != null) System.arraycopy(currentDependencies, 0, dependencies, 0, currentDependencies.length);
				dependencies[dependencies.length - 1] = "flywayMigrationRunner";
				entityManager.setDependsOn(dependencies);
			}
		};
	}
}
