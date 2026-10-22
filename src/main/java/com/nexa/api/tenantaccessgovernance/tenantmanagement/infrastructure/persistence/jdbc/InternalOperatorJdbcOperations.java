package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Function;

/** Transaction boundary over the isolated central internal-operator database pool. */
public final class InternalOperatorJdbcOperations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;

    public InternalOperatorJdbcOperations(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "Internal-operator DataSource is required");
        this.jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactionManager);
    }

    public <T> T readOnly(Function<JdbcTemplate, T> operation) {
        return Objects.requireNonNull(readOnly.execute(status -> operation.apply(jdbc)));
    }

    public <T> T readWrite(Function<JdbcTemplate, T> operation) {
        return Objects.requireNonNull(readWrite.execute(status -> operation.apply(jdbc)));
    }
}
