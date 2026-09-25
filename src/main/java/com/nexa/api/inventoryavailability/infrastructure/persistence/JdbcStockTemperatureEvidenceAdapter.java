package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.StockTemperatureEvidencePersistencePort;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.StockTemperatureEvidence;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureEvidenceStatus;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureEvidenceSubject;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

/** JDBC persistence for immutable staged stock temperature evidence. */
@Repository
@Profile("!test")
public class JdbcStockTemperatureEvidenceAdapter implements StockTemperatureEvidencePersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcStockTemperatureEvidenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StockTemperatureEvidence record(RecordStockTemperatureEvidence request) {
        ResolvedSubject subject = resolveSubject(request);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("insert into warehouse.stock_temperature_evidence "
                        + "(id,tenant_id,workspace_id,subject_type,warehouse_id,zone_id,lot_id,value,unit,observed_at,recorded_at,actor_membership_id,status,idempotency_key,request_hash) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?) on conflict on constraint uq_stock_temperature_evidence_actor_key do nothing",
                id, request.tenantId(), request.workspaceId(), request.subjectType().name(), subject.warehouseId(),
                subject.zoneId(), request.lotId(), request.value(), request.unit().name(),
                Timestamp.from(request.observedAt()), Timestamp.from(request.recordedAt()),
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash());
        if (inserted == 1) return load(id, request.tenantId(), request.workspaceId());

        PriorRequest prior = jdbc.query("select id,request_hash from warehouse.stock_temperature_evidence "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?",
                (rs, row) -> new PriorRequest(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElseThrow(() -> error("CONCURRENCY_CONFLICT", false));
        if (!prior.requestHash().equals(request.requestHash())) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
        return load(prior.id(), request.tenantId(), request.workspaceId());
    }

    private ResolvedSubject resolveSubject(RecordStockTemperatureEvidence request) {
        if (request.subjectType() == TemperatureEvidenceSubject.WAREHOUSE) {
            UUID warehouseId = request.warehouseId();
            if (warehouseId == null || !existsWarehouse(request, warehouseId)) throw error("WAREHOUSE_NOT_FOUND", true);
            return new ResolvedSubject(warehouseId, null);
        }
        if (request.lotId() == null) throw error("INVALID_REQUEST", false);
        return jdbc.query("select warehouse_id,zone_id from warehouse.inventory_lot "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new ResolvedSubject(rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("zone_id", UUID.class)),
                request.tenantId(), request.workspaceId(), request.lotId())
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_LOT_NOT_FOUND", true));
    }

    private boolean existsWarehouse(RecordStockTemperatureEvidence request, UUID warehouseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouse.warehouse "
                        + "where tenant_id=? and workspace_id=? and id=?)", Boolean.class,
                request.tenantId(), request.workspaceId(), warehouseId));
    }

    private StockTemperatureEvidence load(UUID id, UUID tenantId, UUID workspaceId) {
        return jdbc.query("select id,subject_type,lot_id,warehouse_id,value,unit,observed_at,recorded_at,actor_membership_id,status "
                        + "from warehouse.stock_temperature_evidence where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new StockTemperatureEvidence(
                        rs.getObject("id", UUID.class),
                        TemperatureEvidenceSubject.valueOf(rs.getString("subject_type")),
                        rs.getObject("lot_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class),
                        rs.getBigDecimal("value"),
                        TemperatureUnit.valueOf(rs.getString("unit")),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getObject("actor_membership_id", UUID.class),
                        TemperatureEvidenceStatus.valueOf(rs.getString("status"))), tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_TEMPERATURE_EVIDENCE_NOT_FOUND", true));
    }

    private static WarehouseOperationsService.WarehouseException error(String code, boolean notFound) {
        return new WarehouseOperationsService.WarehouseException(code, notFound);
    }

    private record ResolvedSubject(UUID warehouseId, UUID zoneId) { }
    private record PriorRequest(UUID id, String requestHash) { }
}
