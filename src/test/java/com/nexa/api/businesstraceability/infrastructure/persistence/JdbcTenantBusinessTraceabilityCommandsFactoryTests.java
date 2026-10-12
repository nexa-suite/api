package com.nexa.api.businesstraceability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcTenantBusinessTraceabilityCommandsFactoryTests {
    private final JdbcTemplate tenantJdbc = mock(JdbcTemplate.class);
    private final CanonicalOutboxPort tenantOutbox = mock(CanonicalOutboxPort.class);
    private final JdbcTenantBusinessTraceabilityCommandsFactory factory =
            new JdbcTenantBusinessTraceabilityCommandsFactory(new ObjectMapper());

    @Test
    void tenantBindingRequiresTransactionAndUsesSuppliedJdbcAndOutbox() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-09T12:00:00Z");
        BusinessTraceabilityCommands commands = factory.bindTo(tenantJdbc, tenantOutbox);
        BusinessTraceabilityCommands.TraceRequest request = new BusinessTraceabilityCommands.TraceRequest(
                tenantId, workspaceId, null, "SYSTEM", "FixtureConfirmed", "SalesOrder", subjectId,
                "correlation-1", "fixture-confirmed-1", Map.of("source", "test"), occurredAt);

        assertThatThrownBy(() -> commands.record(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Business traceability must join an active transaction");
        verifyNoInteractions(tenantJdbc, tenantOutbox);

        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        SingleConnectionDataSource transactionDataSource = new SingleConnectionDataSource(connection, true);
        try {
            new TransactionTemplate(new DataSourceTransactionManager(transactionDataSource))
                    .executeWithoutResult(status -> commands.record(request));
        } finally {
            transactionDataSource.destroy();
        }

        verify(tenantJdbc).update(startsWith("insert into audit.event"), any(Object[].class));
        verify(tenantOutbox).append(eq("BusinessFactTraced.v1"), eq("BusinessTraceabilityRecord"),
                any(UUID.class), eq(tenantId), eq(workspaceId), eq(occurredAt), eq("correlation-1"),
                isNull(), eq("1.0"), eq("fixture-confirmed-1"), anyMap());
    }
}
