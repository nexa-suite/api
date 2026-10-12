package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationCommands;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.LegacyCustomerCreditInitializationQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-session-bound credit setup query and command. The caller owns the enclosing transaction. */
public final class JdbcCreditAccountConfigurationAdapter
        implements CreditAccountConfigurationQuery, CreditAccountConfigurationCommands {
    private static final String OPERATION = "credit-account-configuration";
    private final JdbcTemplate jdbc;
    private final BusinessTraceabilityCommands traceability;
    private final ObjectMapper mapper;
    private final LegacyCustomerCreditInitializationQuery legacyCredit;

    public JdbcCreditAccountConfigurationAdapter(JdbcTemplate jdbc,
            BusinessTraceabilityCommands traceability, ObjectMapper mapper,
            LegacyCustomerCreditInitializationQuery legacyCredit) {
        this.jdbc = Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
        this.traceability = Objects.requireNonNull(traceability, "Tenant traceability commands are required");
        this.mapper = Objects.requireNonNull(mapper, "Object mapper is required");
        this.legacyCredit = Objects.requireNonNull(legacyCredit, "Tenant legacy credit query is required");
    }

    @Override
    public Snapshot find(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency) {
        requireScope(tenantId, workspaceId, customerAccountId, currency);
        AccountRow account = account(tenantId, workspaceId, customerAccountId, currency, false);
        if (account == null) return Snapshot.notConfigured(customerAccountId, normalizeCurrency(currency));
        BigDecimal reservations = activeReservations(tenantId, workspaceId, account.id());
        if (reservations.compareTo(account.reservedExposure()) != 0) throw error("DATA_INTEGRITY_CONFLICT");
        return snapshot(account, outstandingReceivables(tenantId, workspaceId, customerAccountId, currency), reservations);
    }

    @Override
    public Snapshot configure(Request request) {
        Objects.requireNonNull(request, "Credit configuration request is required");
        lockIdempotency(request);
        Optional<Snapshot> replay = findReplay(request);
        if (replay.isPresent()) return replay.get();

        lockAccountScope(request);
        // Match the account lock used by credit reservation commands before reading the
        // receivable and reservation buckets that define the limit floor.
        AccountRow account = account(request.tenantId(), request.workspaceId(), request.customerAccountId(),
                request.currency(), true);
        BigDecimal outstanding = lockAndSumOutstanding(request);
        Snapshot result = request.createIfAbsent()
                ? create(request, account, outstanding)
                : update(request, account, outstanding);
        AccountRow resultingAccount = account(request.tenantId(), request.workspaceId(), request.customerAccountId(),
                request.currency(), false);
        if (resultingAccount == null) throw error("DATA_INTEGRITY_CONFLICT");

        traceability.record(new BusinessTraceabilityCommands.TraceRequest(
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PLATFORM",
                "CREDIT_ACCOUNT_CONFIGURATION_CHANGED", "CreditAccount", resultingAccount.id(),
                request.idempotencyKey(), "credit-account-configuration-" + request.idempotencyKey(),
                Map.of("clientAccountId", request.customerAccountId(), "currency", result.currency(),
                        "creditLimit", result.creditLimit(), "status", result.status().name(),
                        "version", result.version(), "used", result.used()), request.now()));
        persistIdempotentResult(request, result, resultingAccount.id());
        return result;
    }

    private Snapshot create(Request request, AccountRow existing, BigDecimal outstanding) {
        if (existing != null) throw error("PRECONDITION_FAILED");
        BigDecimal legacyExposure = legacyCredit.find(request.tenantId(), request.workspaceId(),
                        request.customerAccountId(), request.currency())
                .map(LegacyCustomerCreditInitializationQuery.Snapshot::initialExposure)
                .orElse(BigDecimal.ZERO);
        if (legacyExposure.signum() > 0 && outstanding.signum() > 0) {
            throw error("DATA_INTEGRITY_CONFLICT");
        }
        BigDecimal used = legacyExposure.add(outstanding);
        if (used.compareTo(request.creditLimit()) > 0) throw error("CREDIT_LIMIT_BELOW_USED");

        UUID id = UUID.randomUUID();
        String status = request.active() ? "ACTIVE" : "SUSPENDED";
        int inserted = jdbc.update("insert into payments.credit_account "
                        + "(id,tenant_id,workspace_id,client_account_id,currency,credit_limit,credit_exposure,reserved_exposure,status,version,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,0,?,0,?,?) on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                id, request.tenantId(), request.workspaceId(), request.customerAccountId(), request.currency(),
                request.creditLimit(), legacyExposure, status, Timestamp.from(request.now()), Timestamp.from(request.now()));
        if (inserted != 1) throw error("PRECONDITION_FAILED");
        return new Snapshot(request.customerAccountId(), request.currency(), status(status), request.creditLimit(),
                legacyExposure, outstanding, BigDecimal.ZERO, 0L);
    }

    private Snapshot update(Request request, AccountRow account, BigDecimal outstanding) {
        if (account == null) throw error("PRECONDITION_FAILED");
        if ("CLOSED".equals(account.status())) throw error("CREDIT_ACCOUNT_CLOSED");
        if (account.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        BigDecimal reserved = activeReservations(request.tenantId(), request.workspaceId(), account.id());
        if (reserved.compareTo(account.reservedExposure()) != 0) throw error("DATA_INTEGRITY_CONFLICT");
        BigDecimal used = account.financedExposure().add(outstanding).add(reserved);
        if (used.compareTo(request.creditLimit()) > 0) throw error("CREDIT_LIMIT_BELOW_USED");
        String nextStatus = request.active() ? "ACTIVE" : "SUSPENDED";
        long nextVersion = account.version() + 1;
        int updated = jdbc.update("update payments.credit_account set credit_limit=?,status=?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and status<>'CLOSED'",
                request.creditLimit(), nextStatus, Timestamp.from(request.now()), request.tenantId(),
                request.workspaceId(), account.id(), request.expectedVersion());
        if (updated != 1) throw error("CONCURRENCY_CONFLICT");
        return new Snapshot(request.customerAccountId(), request.currency(), status(nextStatus), request.creditLimit(),
                account.financedExposure(), outstanding, reserved, nextVersion);
    }

    private Optional<Snapshot> findReplay(Request request) {
        return jdbc.query("select record.request_hash,coalesce(response.response_json,record.response_json) "
                        + "from sales.idempotency_record record left join sales.idempotency_response response "
                        + "on response.tenant_id=record.tenant_id and response.workspace_id=record.workspace_id "
                        + "and response.actor_membership_id=record.actor_membership_id and response.operation=record.operation "
                        + "and response.idempotency_key=record.idempotency_key "
                        + "where record.tenant_id=? and record.workspace_id=? and record.actor_membership_id=? "
                        + "and record.operation=? and record.idempotency_key=?",
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    String storedHash = rs.getString(1);
                    String responseJson = rs.getString(2);
                    if (!request.requestHash().equalsIgnoreCase(storedHash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
                    if (responseJson == null || responseJson.isBlank()) throw error("DATA_INTEGRITY_CONFLICT");
                    try {
                        return Optional.of(mapper.readValue(responseJson, Snapshot.class));
                    } catch (Exception exception) {
                        throw error("DATA_INTEGRITY_CONFLICT");
                    }
                }, request.tenantId(), request.workspaceId(), request.actorMembershipId(), OPERATION,
                request.idempotencyKey());
    }

    private void persistIdempotentResult(Request request, Snapshot result, UUID resourceId) {
        String json;
        try {
            json = mapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Credit configuration response could not be serialized", exception);
        }
        jdbc.update("insert into sales.idempotency_record "
                        + "(id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,request_hash,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.actorMembershipId(), OPERATION,
                request.idempotencyKey(), resourceId, result.version(), request.requestHash(), Timestamp.from(request.now()));
        jdbc.update("insert into sales.idempotency_response "
                        + "(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,response_json,created_at) "
                        + "values (?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), OPERATION,
                request.idempotencyKey(), json, Timestamp.from(request.now()));
    }

    private BigDecimal lockAndSumOutstanding(Request request) {
        return jdbc.query("select amount,coalesce(adjustment_total,0) adjustment_total,amount_paid "
                        + "from payments.receivable where tenant_id=? and workspace_id=? and client_account_id=? "
                        + "and currency=? and status in ('OPEN','PARTIALLY_PAID','OVERDUE') order by id for update",
                rs -> {
                    BigDecimal total = BigDecimal.ZERO;
                    while (rs.next()) total = total.add(rs.getBigDecimal("amount")
                            .add(rs.getBigDecimal("adjustment_total")).subtract(rs.getBigDecimal("amount_paid")));
                    return total;
                }, request.tenantId(), request.workspaceId(), request.customerAccountId(), request.currency());
    }

    private BigDecimal outstandingReceivables(UUID tenantId, UUID workspaceId, UUID customerAccountId,
            String currency) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(amount+coalesce(adjustment_total,0)-amount_paid),0) "
                        + "from payments.receivable where tenant_id=? and workspace_id=? and client_account_id=? "
                        + "and currency=? and status in ('OPEN','PARTIALLY_PAID','OVERDUE')",
                BigDecimal.class, tenantId, workspaceId, customerAccountId, currency);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal activeReservations(UUID tenantId, UUID workspaceId, UUID creditAccountId) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(amount),0) from payments.credit_reservation "
                        + "where tenant_id=? and workspace_id=? and credit_account_id=? and status='RESERVED'",
                BigDecimal.class, tenantId, workspaceId, creditAccountId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private AccountRow account(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency,
            boolean forUpdate) {
        String sql = "select id,client_account_id,currency,credit_limit,credit_exposure,reserved_exposure,status,version "
                + "from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=?"
                + (forUpdate ? " for update" : "");
        return jdbc.query(sql, (rs, row) -> new AccountRow(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getString("currency"),
                        rs.getBigDecimal("credit_limit"), rs.getBigDecimal("credit_exposure"),
                        rs.getBigDecimal("reserved_exposure"), rs.getString("status"), rs.getLong("version")),
                tenantId, workspaceId, customerAccountId, normalizeCurrency(currency)).stream().findFirst().orElse(null);
    }

    private static Snapshot snapshot(AccountRow account, BigDecimal outstanding, BigDecimal reservations) {
        return new Snapshot(account.customerAccountId(), account.currency(), status(account.status()), account.limit(),
                account.financedExposure(), outstanding, reservations, account.version());
    }

    private void lockIdempotency(Request request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|" + request.actorMembershipId()
                        + "|" + OPERATION + "|" + request.idempotencyKey());
    }

    private void lockAccountScope(Request request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|" + request.customerAccountId()
                        + "|" + request.currency());
    }

    private static void requireScope(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency) {
        if (tenantId == null || workspaceId == null || customerAccountId == null) {
            throw new IllegalArgumentException("Credit configuration scope is required");
        }
        normalizeCurrency(currency);
    }

    private static String normalizeCurrency(String currency) {
        String normalized = currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency is invalid");
        return normalized;
    }

    private static CreditAccountConfigurationQuery.Status status(String value) {
        return CreditAccountConfigurationQuery.Status.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static CreditReceivableOperationException error(String code) {
        return new CreditReceivableOperationException(code);
    }

    private record AccountRow(UUID id, UUID customerAccountId, String currency, BigDecimal limit,
                              BigDecimal financedExposure, BigDecimal reservedExposure, String status, long version) { }
}
