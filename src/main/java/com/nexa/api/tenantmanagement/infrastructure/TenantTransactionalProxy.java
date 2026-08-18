package com.nexa.api.tenantmanagement.infrastructure;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Keeps transaction demarcation in infrastructure while application services remain framework-free. */
final class TenantTransactionalProxy {
    private TenantTransactionalProxy() { }

    static <T> T required(T target, Class<T> contract, PlatformTransactionManager transactionManager) {
        MatchAlwaysTransactionAttributeSource attributes = new MatchAlwaysTransactionAttributeSource();
        attributes.setTransactionAttribute(new DefaultTransactionAttribute());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(contract);
        factory.setProxyTargetClass(false);
        factory.addAdvice(new TransactionInterceptor(transactionManager, attributes));
        return contract.cast(factory.getProxy());
    }
}
