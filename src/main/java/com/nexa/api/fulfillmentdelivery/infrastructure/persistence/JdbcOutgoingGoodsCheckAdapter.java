package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.port.OutgoingGoodsCheckPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels.DiscrepancyResolution;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only BC-06 storage for physical outbound comparisons. */
@Repository
@Profile("!test")
public class JdbcOutgoingGoodsCheckAdapter implements OutgoingGoodsCheckPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcOutgoingGoodsCheckAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PersistResult record(RecordRequest request) {
        List<UUID> inserted = jdbc.query(
                "insert into logistics.fulfillment_outgoing_goods_check "
                        + "(id,tenant_id,workspace_id,fulfillment_id,fulfillment_version,physical_allocation_id,"
                        + "physical_allocation_version,actor_membership_id,idempotency_key,request_hash,matches,checked_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict "
                        + "(tenant_id,workspace_id,actor_membership_id,idempotency_key) do nothing returning id",
                (rs, row) -> rs.getObject("id", UUID.class), UUID.randomUUID(), request.tenantId(),
                request.workspaceId(), request.fulfillmentId(), request.fulfillmentVersion(),
                request.physicalAllocationId(), request.physicalAllocationVersion(),
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash(),
                request.matches(), Timestamp.from(request.checkedAt()));
        if (inserted.isEmpty()) {
            Header prior = jdbc.query(
                    "select id,fulfillment_id,fulfillment_version,physical_allocation_id,"
                            + "physical_allocation_version,matches,actor_membership_id,checked_at,request_hash "
                            + "from logistics.fulfillment_outgoing_goods_check where tenant_id=? and workspace_id=? "
                            + "and actor_membership_id=? and idempotency_key=?",
                    (rs, row) -> new Header(rs.getObject("id", UUID.class),
                            rs.getObject("fulfillment_id", UUID.class), rs.getLong("fulfillment_version"),
                            rs.getObject("physical_allocation_id", UUID.class),
                            rs.getLong("physical_allocation_version"), rs.getBoolean("matches"),
                            rs.getObject("actor_membership_id", UUID.class),
                            rs.getTimestamp("checked_at").toInstant(), rs.getString("request_hash")),
                    request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                    request.idempotencyKey()).stream().findFirst().orElse(null);
            if (prior == null) return new PersistResult(null, false, true);
            if (!prior.requestHash().equals(request.requestHash())) {
                return new PersistResult(null, false, true);
            }
            return new PersistResult(load(request.tenantId(), request.workspaceId(), prior), true, false);
        }

        UUID id = inserted.getFirst();
        for (LineFact line : request.lines()) {
            jdbc.update("insert into logistics.fulfillment_outgoing_goods_check_line "
                            + "(tenant_id,workspace_id,check_id,physical_allocation_line_id,sku_id,expected_lot_id,"
                            + "observed_lot_id,expected_quantity,observed_quantity,unit,matches) values (?,?,?,?,?,?,?,?,?,?,?)",
                    request.tenantId(), request.workspaceId(), id, line.physicalAllocationLineId(), line.skuId(),
                    line.expectedLotId(), line.observedLotId(), line.expectedQuantity(),
                    line.observedQuantity(), line.unit(), line.matches());
        }
        Header header = new Header(id, request.fulfillmentId(), request.fulfillmentVersion(),
                request.physicalAllocationId(), request.physicalAllocationVersion(), request.matches(),
                request.actorMembershipId(), request.checkedAt(), request.requestHash());
        return new PersistResult(load(request.tenantId(), request.workspaceId(), header), false, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredCheck> latest(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        Header header = jdbc.query(
                "select id,fulfillment_id,fulfillment_version,physical_allocation_id,"
                        + "physical_allocation_version,matches,actor_membership_id,checked_at,request_hash "
                        + "from logistics.fulfillment_outgoing_goods_check where tenant_id=? and workspace_id=? "
                        + "and fulfillment_id=? order by checked_at desc,id desc limit 1",
                (rs, row) -> new Header(rs.getObject("id", UUID.class),
                        rs.getObject("fulfillment_id", UUID.class), rs.getLong("fulfillment_version"),
                        rs.getObject("physical_allocation_id", UUID.class),
                        rs.getLong("physical_allocation_version"), rs.getBoolean("matches"),
                        rs.getObject("actor_membership_id", UUID.class),
                        rs.getTimestamp("checked_at").toInstant(), rs.getString("request_hash")),
                tenantId, workspaceId, fulfillmentId).stream().findFirst().orElse(null);
        return header == null ? Optional.empty() : Optional.of(load(tenantId, workspaceId, header));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredCheck> latestUnresolvedDiscrepancy(UUID tenantId, UUID workspaceId,
                                                              UUID fulfillmentId, UUID physicalAllocationId,
                                                              long physicalAllocationVersion) {
        Header header = jdbc.query(
                "select c.id,c.fulfillment_id,c.fulfillment_version,c.physical_allocation_id,"
                        + "c.physical_allocation_version,c.matches,c.actor_membership_id,c.checked_at,c.request_hash "
                        + "from logistics.fulfillment_outgoing_goods_check c where c.tenant_id=? and c.workspace_id=? "
                        + "and c.fulfillment_id=? and c.physical_allocation_id=? "
                        + "and c.physical_allocation_version=? and c.matches=false and not exists ("
                        + "select 1 from logistics.fulfillment_outgoing_discrepancy_resolution r "
                        + "where r.tenant_id=c.tenant_id and r.workspace_id=c.workspace_id "
                        + "and r.discrepancy_check_id=c.id) order by c.checked_at desc,c.id desc limit 1",
                (rs, row) -> new Header(rs.getObject("id", UUID.class),
                        rs.getObject("fulfillment_id", UUID.class), rs.getLong("fulfillment_version"),
                        rs.getObject("physical_allocation_id", UUID.class),
                        rs.getLong("physical_allocation_version"), rs.getBoolean("matches"),
                        rs.getObject("actor_membership_id", UUID.class),
                        rs.getTimestamp("checked_at").toInstant(), rs.getString("request_hash")),
                tenantId, workspaceId, fulfillmentId, physicalAllocationId, physicalAllocationVersion)
                .stream().findFirst().orElse(null);
        return header == null ? Optional.empty() : Optional.of(load(tenantId, workspaceId, header));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredResolution> findResolutionByIdempotencyKey(UUID tenantId, UUID workspaceId,
                                                                      UUID actorMembershipId,
                                                                      String idempotencyKey) {
        return jdbc.query("select id,fulfillment_id,fulfillment_version,physical_allocation_id,"
                        + "physical_allocation_version,discrepancy_check_id,matching_check_id,actor_membership_id,"
                        + "reason,resolved_at,request_hash from logistics.fulfillment_outgoing_discrepancy_resolution "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?",
                (rs, row) -> new StoredResolution(new DiscrepancyResolution(rs.getObject("id", UUID.class),
                        rs.getObject("fulfillment_id", UUID.class), rs.getLong("fulfillment_version"),
                        rs.getObject("physical_allocation_id", UUID.class),
                        rs.getLong("physical_allocation_version"), rs.getObject("discrepancy_check_id", UUID.class),
                        rs.getObject("matching_check_id", UUID.class), rs.getObject("actor_membership_id", UUID.class),
                        rs.getString("reason"), rs.getTimestamp("resolved_at").toInstant(), false, false),
                        rs.getString("request_hash")),
                tenantId, workspaceId, actorMembershipId, idempotencyKey).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResolution resolve(ResolutionRequest request) {
        List<UUID> inserted = jdbc.query("insert into logistics.fulfillment_outgoing_discrepancy_resolution "
                        + "(id,tenant_id,workspace_id,fulfillment_id,fulfillment_version,physical_allocation_id,"
                        + "physical_allocation_version,discrepancy_check_id,matching_check_id,actor_membership_id,"
                        + "idempotency_key,request_hash,reason,resolved_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "on conflict do nothing returning id", (rs, row) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.fulfillmentId(),
                request.fulfillmentVersion(), request.physicalAllocationId(), request.physicalAllocationVersion(), request.discrepancyCheckId(),
                request.matchingCheckId(), request.actorMembershipId(), request.idempotencyKey(),
                request.requestHash(), request.reason(), Timestamp.from(request.resolvedAt()));
        if (inserted.isEmpty()) {
            return findResolutionByIdempotencyKey(request.tenantId(), request.workspaceId(),
                    request.actorMembershipId(), request.idempotencyKey()).orElse(null);
        }
        return new StoredResolution(new DiscrepancyResolution(inserted.getFirst(), request.fulfillmentId(), request.fulfillmentVersion(),
                request.physicalAllocationId(), request.physicalAllocationVersion(), request.discrepancyCheckId(),
                request.matchingCheckId(), request.actorMembershipId(), request.reason(), request.resolvedAt(),
                true, false), request.requestHash());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenDiscrepancy(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                      UUID physicalAllocationId, long physicalAllocationVersion) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from logistics.fulfillment_outgoing_goods_check c "
                        + "where c.tenant_id=? and c.workspace_id=? and c.fulfillment_id=? "
                        + "and c.physical_allocation_id=? and c.physical_allocation_version=? and c.matches=false "
                        + "and not exists(select 1 from logistics.fulfillment_outgoing_discrepancy_resolution r "
                        + "where r.tenant_id=c.tenant_id and r.workspace_id=c.workspace_id "
                        + "and r.discrepancy_check_id=c.id))",
                Boolean.class, tenantId, workspaceId, fulfillmentId, physicalAllocationId,
                physicalAllocationVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCurrentMatch(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                   long fulfillmentVersion, UUID physicalAllocationId,
                                   long physicalAllocationVersion) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from logistics.fulfillment_outgoing_goods_check "
                        + "where tenant_id=? and workspace_id=? and fulfillment_id=? and fulfillment_version=? "
                        + "and physical_allocation_id=? and physical_allocation_version=? and matches=true) "
                        + "and not exists(select 1 from logistics.fulfillment_outgoing_goods_check c "
                        + "where c.tenant_id=? and c.workspace_id=? and c.fulfillment_id=? "
                        + "and c.physical_allocation_id=? and c.physical_allocation_version=? and c.matches=false "
                        + "and not exists(select 1 from logistics.fulfillment_outgoing_discrepancy_resolution r "
                        + "where r.tenant_id=c.tenant_id and r.workspace_id=c.workspace_id "
                        + "and r.discrepancy_check_id=c.id))",
                Boolean.class, tenantId, workspaceId, fulfillmentId, fulfillmentVersion, physicalAllocationId,
                        physicalAllocationVersion, tenantId, workspaceId, fulfillmentId, physicalAllocationId,
                        physicalAllocationVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> currentMatchId(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                         long fulfillmentVersion, UUID physicalAllocationId,
                                         long physicalAllocationVersion) {
        return jdbc.query("select id from logistics.fulfillment_outgoing_goods_check "
                        + "where tenant_id=? and workspace_id=? and fulfillment_id=? and fulfillment_version=? "
                        + "and physical_allocation_id=? and physical_allocation_version=? and matches=true "
                        + "and not exists(select 1 from logistics.fulfillment_outgoing_goods_check c where "
                        + "c.tenant_id=? and c.workspace_id=? and c.fulfillment_id=? and c.physical_allocation_id=? "
                        + "and c.physical_allocation_version=? and c.matches=false and not exists ("
                        + "select 1 from logistics.fulfillment_outgoing_discrepancy_resolution r "
                        + "where r.tenant_id=c.tenant_id and r.workspace_id=c.workspace_id "
                        + "and r.discrepancy_check_id=c.id)) order by checked_at desc,id desc limit 1",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, fulfillmentId,
                fulfillmentVersion, physicalAllocationId, physicalAllocationVersion, tenantId, workspaceId,
                fulfillmentId, physicalAllocationId, physicalAllocationVersion).stream().findFirst();
    }

    private StoredCheck load(UUID tenantId, UUID workspaceId, Header header) {
        List<LineFact> lines = jdbc.query(
                "select physical_allocation_line_id,sku_id,expected_lot_id,observed_lot_id,"
                        + "expected_quantity,observed_quantity,unit,matches from "
                        + "logistics.fulfillment_outgoing_goods_check_line where tenant_id=? and workspace_id=? "
                        + "and check_id=? order by physical_allocation_line_id",
                (rs, row) -> new LineFact(rs.getObject("physical_allocation_line_id", UUID.class),
                        rs.getObject("sku_id", UUID.class), rs.getObject("expected_lot_id", UUID.class),
                        rs.getObject("observed_lot_id", UUID.class), rs.getBigDecimal("expected_quantity"),
                        rs.getBigDecimal("observed_quantity"), rs.getString("unit"), rs.getBoolean("matches")),
                tenantId, workspaceId, header.id());
        return new StoredCheck(header.id(), header.fulfillmentId(), header.fulfillmentVersion(),
                header.physicalAllocationId(), header.physicalAllocationVersion(), header.matches(),
                header.actorMembershipId(), header.checkedAt(), lines);
    }

    private record Header(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                          UUID physicalAllocationId, long physicalAllocationVersion,
                          boolean matches, UUID actorMembershipId,
                          java.time.Instant checkedAt, String requestHash) { }
}
