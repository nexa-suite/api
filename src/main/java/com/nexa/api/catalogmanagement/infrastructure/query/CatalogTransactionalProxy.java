package com.nexa.api.catalogmanagement.infrastructure.query;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Makes Catalog command boundaries explicit without leaking Spring into Application. */
final class CatalogTransactionalProxy {
    private CatalogTransactionalProxy() { }

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
