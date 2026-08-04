package com.nexa.api.shared.infrastructure.security;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/** Installs selective PostgreSQL RLS context only for the real local/runtime profile. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class RlsDataSourceConfiguration {
    @Bean
    static BeanPostProcessor rlsDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof RlsScopedDataSource)) {
                    return new RlsScopedDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
