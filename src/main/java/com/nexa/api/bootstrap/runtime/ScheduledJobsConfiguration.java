package com.nexa.api.bootstrap.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background schedulers stay off during the explicit local fixture CLI. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!local-fixtures")
public class ScheduledJobsConfiguration { }
