package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityCommandsFactory;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantBusinessTraceabilityBindingsFactoryTests {
    @Test
    void bindsCommandsAndCanonicalOutboxToSameRouterJdbcSession() {
        JdbcTemplate tenantJdbc = mock(JdbcTemplate.class);
        BusinessTraceabilityCommands tenantCommands = mock(BusinessTraceabilityCommands.class);
        TenantBusinessTraceabilityCommandsFactory commandsFactory = mock(TenantBusinessTraceabilityCommandsFactory.class);
        when(commandsFactory.bindTo(same(tenantJdbc), any())).thenReturn(tenantCommands);

        TenantBusinessTraceabilityWorkerFactory workerFactory = mock(TenantBusinessTraceabilityWorkerFactory.class);
        TenantBusinessTraceabilityBindingsFactory factory = new TenantBusinessTraceabilityBindingsFactory(
                commandsFactory, workerFactory);
        TenantBusinessTraceabilityBindingsFactory.Bindings bindings = factory.bindTo(tenantJdbc);

        assertThat(bindings.commands()).isSameAs(tenantCommands);
        verify(commandsFactory).bindTo(same(tenantJdbc), same(bindings.canonicalOutbox()));

        bindings.canonicalOutbox().append("TenantScopedTest.v1", "Test", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-09T12:00:00Z"),
                "correlation-1", null, "1.0", "occurrence-1", Map.of("source", "test"));

        verify(tenantJdbc).update(startsWith("insert into integration.outbox_event"), any(Object[].class));
    }
}
