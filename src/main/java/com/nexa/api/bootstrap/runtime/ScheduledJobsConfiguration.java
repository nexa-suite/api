package com.nexa.api.bootstrap.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background schedulers stay off during fixture-only and integration-test runs. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!local-fixtures")
@ConditionalOnProperty(name = "nexa.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobsConfiguration { }
