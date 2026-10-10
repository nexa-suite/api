package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityCommandsFactory;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerFactory;
import com.nexa.api.bootstrap.runtime.events.JdbcCanonicalOutboxAdapter;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Composes the BC-11 commands and shared outbox against the exact JDBC session supplied by the Tenant router.
 * Call only from that router callback; this has no central-data-source fallback.
 */
@Component
public final class TenantBusinessTraceabilityBindingsFactory {
    private final TenantBusinessTraceabilityCommandsFactory commandsFactory;
    private final TenantBusinessTraceabilityWorkerFactory workerFactory;

    public TenantBusinessTraceabilityBindingsFactory(TenantBusinessTraceabilityCommandsFactory commandsFactory,
            TenantBusinessTraceabilityWorkerFactory workerFactory) {
        this.commandsFactory = Objects.requireNonNull(commandsFactory, "Tenant traceability factory is required");
        this.workerFactory = Objects.requireNonNull(workerFactory, "Tenant traceability worker factory is required");
    }

    public Bindings bindTo(JdbcTemplate tenantJdbc) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        CanonicalOutboxPort tenantOutbox = new JdbcCanonicalOutboxAdapter(jdbc);
        BusinessTraceabilityCommands tenantCommands = Objects.requireNonNull(
                commandsFactory.bindTo(jdbc, tenantOutbox), "Tenant traceability factory returned no commands");
        return new Bindings(tenantCommands, tenantOutbox);
    }

    public WorkerBindings bindWorker(JdbcTemplate tenantJdbc) {
        Bindings owner = bindTo(tenantJdbc);
        return new WorkerBindings(workerFactory.bindTo(tenantJdbc, owner.commands()));
    }

    public record Bindings(BusinessTraceabilityCommands commands, CanonicalOutboxPort canonicalOutbox) {
        public Bindings {
            Objects.requireNonNull(commands, "Tenant traceability commands are required");
            Objects.requireNonNull(canonicalOutbox, "Tenant canonical outbox port is required");
        }
    }

    public record WorkerBindings(TenantBusinessTraceabilityWorkerFactory.Worker worker) {
        public WorkerBindings {
            Objects.requireNonNull(worker, "Tenant traceability worker is required");
        }
    }
}
