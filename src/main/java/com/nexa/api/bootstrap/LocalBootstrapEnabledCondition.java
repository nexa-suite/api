package com.nexa.api.bootstrap;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class LocalBootstrapEnabledCondition implements Condition {
	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return Boolean.parseBoolean(context.getEnvironment().getProperty("NEXA_DEV_BOOTSTRAP_ENABLED", "false"))
				|| Boolean.parseBoolean(context.getEnvironment().getProperty("nexa.dev-bootstrap.enabled", "false"));
	}
}
