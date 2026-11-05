package com.nexa.api.bootstrap.runtime.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.util.function.Supplier;

/** Central metadata-only pool; credentials are distinct from nexa_runtime. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class WorkspaceScopeReaderConfiguration {
    @Bean(name = "workspaceScopeReaderPool", destroyMethod = "close")
    WorkspaceScopeReaderPool workspaceScopeReaderPool(
            @Value("${spring.datasource.url}") String centralJdbcUrl,
            @Value("${NEXA_WORKSPACE_SCOPE_READER_PASSWORD:}") String password,
            @Value("${nexa.database.workspace-scope-reader.maximum-pool-size:2}") int maximumPoolSize) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Dedicated central Workspace scope-reader credentials are required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 4) {
            throw new IllegalArgumentException("Workspace scope-reader pool size must be between 1 and 4");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(centralJdbcUrl);
        config.setUsername("nexa_workspace_scope_reader_login");
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setPoolName("workspace-scope-reader");
        config.setReadOnly(true);
        return new WorkspaceScopeReaderPool(new HikariDataSource(config));
    }

    @Bean(name = "workspaceScopeReaderDataSourceProvider")
    Supplier<DataSource> workspaceScopeReaderDataSourceProvider(
            @Qualifier("workspaceScopeReaderPool") WorkspaceScopeReaderPool pool) {
        DataSource scoped = RlsScopedDataSource.forCurrentCentralScope(pool.dataSource());
        return () -> scoped;
    }

    static final class WorkspaceScopeReaderPool implements AutoCloseable {
        private final HikariDataSource pool;

        private WorkspaceScopeReaderPool(HikariDataSource pool) {
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
