package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Keeps context-selection transaction demarcation in IAM infrastructure. */
final class IamTransactionalProxy {
	private IamTransactionalProxy() { }

	static <T> T required(Object target, Class<T> contract, PlatformTransactionManager transactionManager) {
		MatchAlwaysTransactionAttributeSource attributes = new MatchAlwaysTransactionAttributeSource();
		attributes.setTransactionAttribute(new DefaultTransactionAttribute());
		ProxyFactory factory = new ProxyFactory(target);
		factory.setInterfaces(contract);
		factory.setProxyTargetClass(false);
		factory.addAdvice(new TransactionInterceptor(transactionManager, attributes));
		return contract.cast(factory.getProxy());
	}
}
