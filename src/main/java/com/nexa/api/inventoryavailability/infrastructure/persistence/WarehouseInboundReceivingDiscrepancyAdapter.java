package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** BC-05 owner for immutable inbound discrepancy observations and review requests. */
@Repository
@Profile("!test")
public class WarehouseInboundReceivingDiscrepancyAdapter implements InboundReceivingDiscrepancyCases {
    private final JdbcTemplate jdbc;
    private final WarehouseObjectAccess warehouseAccess;
    private final SellableSkuQuery catalog;

    public WarehouseInboundReceivingDiscrepancyAdapter(JdbcTemplate jdbc,
                                                        WarehouseObjectAccess warehouseAccess,
                                                        SellableSkuQuery catalog) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Warehouse access is required");
        this.catalog = Objects.requireNonNull(catalog, "Catalog query is required");
    }

    @Override
    @Transactional
    public CaseFact create(CreateRequest request) {
        Objects.requireNonNull(request, "Inbound discrepancy request is required");
        lock("inbound-discrepancy-create", request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), request.idempotencyKey());
        Optional<CaseFact> previous = findCreateClaim(request);
        if (previous.isPresent()) {
            CaseFact prior = previous.get();
            requireCurrentGrant(request.tenantId(), request.workspaceId(), request.actorMembershipId(), prior.warehouseId());
            String storedHash = jdbc.queryForObject("select create_request_hash from warehouse.inbound_receiving_discrepancy_case "
                    + "where tenant_id=? and workspace_id=? and id=?", String.class,
                    request.tenantId(), request.workspaceId(), prior.id());
            if (!request.requestHash().equals(storedHash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            return withReplay(prior, true);
        }

        requireCurrentGrant(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.warehouseId());
        requireActiveSkus(request);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into warehouse.inbound_receiving_discrepancy_case "
                        + "(id,tenant_id,workspace_id,warehouse_id,expected_sku_id,observed_sku_id,expected_batch_reference,observed_batch_reference,"
                        + "expected_quantity,observed_quantity,unit,reason,observation_notes,status,version,recorded_by_membership_id,recorded_at,"
                        + "create_idempotency_key,create_request_hash) values (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING_EVIDENCE',0,?,?,?,?)",
                id, request.tenantId(), request.workspaceId(), request.warehouseId(), request.expectedSkuId(), request.observedSkuId(),
                request.expectedBatchReference(), request.observedBatchReference(), request.expectedQuantity(), request.observedQuantity(),
                request.unit(), request.reason(), request.observationNotes(), request.actorMembershipId(), Timestamp.from(request.recordedAt()),
                request.idempotencyKey(), request.requestHash());
        return byId(request.tenantId(), request.workspaceId(), id, false);
    }

    @Override
    @Transactional
    public CaseFact submit(SubmitRequest request) {
        Objects.requireNonNull(request, "Inbound discrepancy submission is required");
        lock("inbound-discrepancy-submit", request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), request.idempotencyKey());
        Optional<SubmitClaim> previous = findSubmitClaim(request);
        if (previous.isPresent()) {
            SubmitClaim prior = previous.get();
            requireCurrentGrant(request.tenantId(), request.workspaceId(), request.actorMembershipId(), prior.fact().warehouseId());
            if (!request.requestHash().equals(prior.requestHash()) || !request.caseId().equals(prior.fact().id())) {
                throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            }
            return withReplay(prior.fact(), true);
        }

        CaseFact current = lockById(request.tenantId(), request.workspaceId(), request.caseId());
        requireCurrentGrant(request.tenantId(), request.workspaceId(), request.actorMembershipId(), current.warehouseId());
        if (!"PENDING_EVIDENCE".equals(current.status())) throw error("INBOUND_DISCREPANCY_NOT_PENDING", false);
        if (current.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT", false);
        int changed = jdbc.update("update warehouse.inbound_receiving_discrepancy_case set status='READY_FOR_REVIEW',version=version+1,"
                        + "evidence_object_id=?,submitted_by_membership_id=?,submitted_at=?,submit_idempotency_key=?,submit_request_hash=? "
                        + "where tenant_id=? and workspace_id=? and id=? and status='PENDING_EVIDENCE' and version=?",
                request.evidenceObjectId(), request.actorMembershipId(), Timestamp.from(request.submittedAt()),
                request.idempotencyKey(), request.requestHash(), request.tenantId(), request.workspaceId(), request.caseId(),
                request.expectedVersion());
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        return byId(request.tenantId(), request.workspaceId(), request.caseId(), false);
    }

    private void requireActiveSkus(CreateRequest request) {
        List<UUID> ids = new ArrayList<>();
        ids.add(request.observedSkuId());
        if (request.expectedSkuId() != null) ids.add(request.expectedSkuId());
        var active = catalog.findInventoryPolicies(request.tenantId(), request.workspaceId(), ids).stream()
                .filter(value -> "ACTIVE".equals(value.status())).map(SellableSkuQuery.InventorySkuSnapshot::id).toList();
        if (active.size() != ids.stream().distinct().count()) throw error("SKU_NOT_FOUND", true);
    }

    private void requireCurrentGrant(UUID tenant, UUID workspace, UUID actor, UUID warehouse) {
        if (!Boolean.TRUE.equals(jdbc.query("select exists(select 1 from warehouse.warehouse "
                        + "where tenant_id=? and workspace_id=? and id=? and status='ACTIVE')",
                (rs, row) -> rs.getBoolean(1), tenant, workspace, warehouse).stream().findFirst().orElse(false))
                || !warehouseAccess.hasActiveGrant(tenant, workspace, actor, warehouse)) {
            throw error("WAREHOUSE_NOT_FOUND", true);
        }
    }

    private Optional<CaseFact> findCreateClaim(CreateRequest request) {
        return jdbc.query("select id,warehouse_id,expected_sku_id,observed_sku_id,expected_batch_reference,observed_batch_reference,"
                        + "expected_quantity,observed_quantity,unit,reason,observation_notes,status,evidence_object_id,version,"
                        + "recorded_by_membership_id,recorded_at,submitted_by_membership_id,submitted_at "
                        + "from warehouse.inbound_receiving_discrepancy_case where tenant_id=? and workspace_id=? "
                        + "and recorded_by_membership_id=? and create_idempotency_key=?",
                (rs, row) -> caseFact(rs),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst();
    }

    private Optional<SubmitClaim> findSubmitClaim(SubmitRequest request) {
        return jdbc.query("select id,warehouse_id,expected_sku_id,observed_sku_id,expected_batch_reference,observed_batch_reference,"
                        + "expected_quantity,observed_quantity,unit,reason,observation_notes,status,evidence_object_id,version,"
                        + "recorded_by_membership_id,recorded_at,submitted_by_membership_id,submitted_at,submit_request_hash "
                        + "from warehouse.inbound_receiving_discrepancy_case where tenant_id=? and workspace_id=? "
                        + "and submitted_by_membership_id=? and submit_idempotency_key=?",
                (rs, row) -> new SubmitClaim(caseFact(rs), rs.getString("submit_request_hash")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst();
    }

    private CaseFact lockById(UUID tenant, UUID workspace, UUID caseId) {
        return jdbc.query("select id,warehouse_id,expected_sku_id,observed_sku_id,expected_batch_reference,observed_batch_reference,"
                        + "expected_quantity,observed_quantity,unit,reason,observation_notes,status,evidence_object_id,version,"
                        + "recorded_by_membership_id,recorded_at,submitted_by_membership_id,submitted_at "
                        + "from warehouse.inbound_receiving_discrepancy_case where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> caseFact(rs), tenant, workspace, caseId).stream().findFirst()
                .orElseThrow(() -> error("INBOUND_DISCREPANCY_NOT_FOUND", true));
    }

    private CaseFact byId(UUID tenant, UUID workspace, UUID caseId, boolean replayed) {
        return jdbc.query("select id,warehouse_id,expected_sku_id,observed_sku_id,expected_batch_reference,observed_batch_reference,"
                        + "expected_quantity,observed_quantity,unit,reason,observation_notes,status,evidence_object_id,version,"
                        + "recorded_by_membership_id,recorded_at,submitted_by_membership_id,submitted_at "
                        + "from warehouse.inbound_receiving_discrepancy_case where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> withReplay(caseFact(rs), replayed), tenant, workspace, caseId).stream().findFirst()
                .orElseThrow(() -> error("INBOUND_DISCREPANCY_NOT_FOUND", true));
    }

    private static CaseFact caseFact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseFact(rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getObject("expected_sku_id", UUID.class), rs.getObject("observed_sku_id", UUID.class),
                rs.getString("expected_batch_reference"), rs.getString("observed_batch_reference"),
                rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("observed_quantity"), rs.getString("unit"),
                rs.getString("reason"), rs.getString("observation_notes"), rs.getString("status"),
                rs.getObject("evidence_object_id", UUID.class), rs.getLong("version"),
                rs.getObject("recorded_by_membership_id", UUID.class), rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("submitted_by_membership_id", UUID.class),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(), false);
    }

    private void lock(String operation, UUID tenant, UUID workspace, UUID actor, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, row) -> rs.getObject(1),
                tenant + "|" + workspace + "|inbound-discrepancy|" + operation + "|" + actor + "|" + key);
    }

    private static CaseFact withReplay(CaseFact fact, boolean replayed) {
        return new CaseFact(fact.id(), fact.warehouseId(), fact.expectedSkuId(), fact.observedSkuId(),
                fact.expectedBatchReference(), fact.observedBatchReference(), fact.expectedQuantity(),
                fact.observedQuantity(), fact.unit(), fact.reason(), fact.observationNotes(), fact.status(),
                fact.evidenceObjectId(), fact.version(), fact.recordedByMembershipId(), fact.recordedAt(),
                fact.submittedByMembershipId(), fact.submittedAt(), replayed);
    }

    private static WarehouseOperationsService.WarehouseException error(String code, boolean notFound) {
        return new WarehouseOperationsService.WarehouseException(code, notFound);
    }

    private record SubmitClaim(CaseFact fact, String requestHash) { }
}
