package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/** Isolated central pool for authenticated, bounded internal-operator workflows. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class InternalOperatorDataSourceConfiguration {
    @Bean(name = "internalOperatorPool", destroyMethod = "close")
    InternalOperatorPool internalOperatorPool(
            @Value("${spring.datasource.url}") String centralJdbcUrl,
            @Value("${NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD:}") String password,
            @Value("${nexa.database.internal-operator.maximum-pool-size:2}") int maximumPoolSize) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Dedicated central internal-operator database credentials are required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 4) {
            throw new IllegalArgumentException("Internal-operator pool size must be between 1 and 4");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(centralJdbcUrl);
        config.setUsername("nexa_internal_operator_login");
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setPoolName("internal-operator");
        config.setConnectionInitSql("SET ROLE nexa_internal_operator");
        return new InternalOperatorPool(new HikariDataSource(config));
    }

    @Bean
    InternalOperatorJdbcOperations internalOperatorJdbcOperations(
            @Qualifier("internalOperatorPool") InternalOperatorPool pool) {
        return new InternalOperatorJdbcOperations(pool.dataSource());
    }

    static final class InternalOperatorPool implements AutoCloseable {
        private final HikariDataSource pool;

        private InternalOperatorPool(HikariDataSource pool) {
            this.pool = pool;
        }

        DataSource dataSource() {
            return pool;
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
