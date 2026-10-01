package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgeRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementResult;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.DispatchInstructionScope;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishedInstruction;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryInstructionPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant/workspace-scoped JDBC adapter for immutable delivery-instruction facts. */
@Repository
@Profile("!test")
public class JdbcDeliveryInstructionPersistenceAdapter implements DeliveryInstructionPersistencePort {
    private static final String PUBLISH_OPERATION = "DISPATCH_INSTRUCTION_PUBLISH";
    private static final String ACK_OPERATION = "DRIVER_INSTRUCTION_ACK";
    private static final String ACTIVE_INSTRUCTION_STATUSES = "('PLANNED','ASSIGNED','DISPATCHED','IN_TRANSIT')";

    private final JdbcTemplate jdbc;

    public JdbcDeliveryInstructionPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
    }

    @Override
    public InstructionSetView findForDriver(UUID tenantId, UUID workspaceId, UUID membershipId, UUID deliveryId) {
        List<InstructionSetRow> rows = jdbc.query("with current_instruction as ("
                        + "select distinct on (r.instruction_id) r.instruction_id,r.kind,r.content,r.instruction_version,r.authored_at,r.authored_by_membership_id,r.source_kind "
                        + "from logistics.delivery_instruction_revision r "
                        + "where r.tenant_id=? and r.workspace_id=? and r.delivery_id=? "
                        + "order by r.instruction_id,r.instruction_version desc) "
                        + "select d.id delivery_id,d.version delivery_version,d.instruction_set_version,"
                        + "i.instruction_id,i.kind,i.content,i.instruction_version,i.authored_at,i.authored_by_membership_id,i.source_kind,ack.acknowledged_at,"
                        + "ack.acknowledged_by_membership_id "
                        + "from logistics.delivery d join logistics.delivery_assignment assignment "
                        + "on assignment.tenant_id=d.tenant_id and assignment.workspace_id=d.workspace_id and assignment.delivery_id=d.id "
                        + "left join current_instruction i on true "
                        + "left join lateral (select a.acknowledged_at,a.acknowledged_by_membership_id "
                        + "from logistics.delivery_instruction_acknowledgement a "
                        + "where a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.delivery_id=d.id "
                        + "and a.instruction_id=i.instruction_id and a.instruction_version=i.instruction_version "
                        + "and a.acknowledged_by_membership_id=? order by a.acknowledged_at desc,a.acknowledgement_id desc limit 1) ack on true "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? and assignment.responsible_membership_id=? "
                        + "and d.status in " + ACTIVE_INSTRUCTION_STATUSES + " "
                        + "order by i.authored_at,i.instruction_id",
                (rs, row) -> {
                    UUID instructionId = rs.getObject("instruction_id", UUID.class);
                    InstructionView instruction = null;
                    if (instructionId != null) {
                        DeliveryInstructionKind kind = DeliveryInstructionKind.valueOf(rs.getString("kind"));
                        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
                        instruction = new InstructionView(instructionId, kind, rs.getString("content"),
                                rs.getLong("instruction_version"), kind.isCritical(), acknowledgedAt != null,
                                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                                rs.getObject("acknowledged_by_membership_id", UUID.class), rs.getString("source_kind"),
                                rs.getObject("authored_by_membership_id", UUID.class),
                                rs.getTimestamp("authored_at").toInstant());
                    }
                    return new InstructionSetRow(rs.getObject("delivery_id", UUID.class),
                            rs.getLong("delivery_version"), rs.getLong("instruction_set_version"), instruction);
                }, tenantId, workspaceId, deliveryId, membershipId, tenantId, workspaceId, deliveryId, membershipId);
        if (rows.isEmpty()) throw error("DELIVERY_NOT_FOUND");
        List<InstructionView> instructions = rows.stream().map(InstructionSetRow::instruction)
                .filter(Objects::nonNull).toList();
        InstructionSetRow first = rows.getFirst();
        return new InstructionSetView(first.deliveryId(), first.deliveryVersion(), first.instructionSetVersion(), instructions);
    }

    @Override
    public InstructionSetView findForDispatch(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        List<InstructionSetRow> rows = jdbc.query("with current_instruction as ("
                        + "select distinct on (r.instruction_id) r.instruction_id,r.kind,r.content,r.instruction_version,r.authored_at,r.authored_by_membership_id,r.source_kind "
                        + "from logistics.delivery_instruction_revision r "
                        + "where r.tenant_id=? and r.workspace_id=? and r.delivery_id=? "
                        + "order by r.instruction_id,r.instruction_version desc) "
                        + "select d.id delivery_id,d.version delivery_version,d.instruction_set_version,"
                        + "i.instruction_id,i.kind,i.content,i.instruction_version,i.authored_at,i.authored_by_membership_id,i.source_kind,ack.acknowledged_at,"
                        + "ack.acknowledged_by_membership_id "
                        + "from logistics.delivery d left join current_instruction i on true "
                        + "left join lateral (select a.acknowledged_at,a.acknowledged_by_membership_id "
                        + "from logistics.delivery_assignment assignment "
                        + "join logistics.delivery_instruction_acknowledgement a "
                        + "on a.tenant_id=assignment.tenant_id and a.workspace_id=assignment.workspace_id "
                        + "and a.delivery_id=assignment.delivery_id "
                        + "and a.acknowledged_by_membership_id=assignment.responsible_membership_id "
                        + "where assignment.tenant_id=d.tenant_id and assignment.workspace_id=d.workspace_id "
                        + "and assignment.delivery_id=d.id and a.instruction_id=i.instruction_id "
                        + "and a.instruction_version=i.instruction_version "
                        + "order by a.acknowledged_at desc,a.acknowledgement_id desc limit 1) ack on true "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? and d.status in "
                        + ACTIVE_INSTRUCTION_STATUSES + " order by i.authored_at,i.instruction_id",
                (rs, row) -> {
                    UUID instructionId = rs.getObject("instruction_id", UUID.class);
                    InstructionView instruction = null;
                    if (instructionId != null) {
                        DeliveryInstructionKind kind = DeliveryInstructionKind.valueOf(rs.getString("kind"));
                        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
                        instruction = new InstructionView(instructionId, kind, rs.getString("content"),
                                rs.getLong("instruction_version"), kind.isCritical(), acknowledgedAt != null,
                                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                                rs.getObject("acknowledged_by_membership_id", UUID.class), rs.getString("source_kind"),
                                rs.getObject("authored_by_membership_id", UUID.class),
                                rs.getTimestamp("authored_at").toInstant());
                    }
                    return new InstructionSetRow(rs.getObject("delivery_id", UUID.class),
                            rs.getLong("delivery_version"), rs.getLong("instruction_set_version"), instruction);
                }, tenantId, workspaceId, deliveryId, tenantId, workspaceId, deliveryId);
        if (rows.isEmpty()) throw error("DELIVERY_NOT_FOUND");
        List<InstructionView> instructions = rows.stream().map(InstructionSetRow::instruction)
                .filter(Objects::nonNull).toList();
        InstructionSetRow first = rows.getFirst();
        return new InstructionSetView(first.deliveryId(), first.deliveryVersion(), first.instructionSetVersion(), instructions);
    }

    @Override
    public Optional<DispatchInstructionScope> findDispatchScope(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select fulfillment_id from logistics.delivery where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> new DispatchInstructionScope(rs.getObject("fulfillment_id", UUID.class)),
                        tenantId, workspaceId, deliveryId)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedInstruction publish(PublishRequest request) {
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                PUBLISH_OPERATION, request.idempotencyKey());
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null) throw error("DELIVERY_NOT_FOUND");
        if (!isDispatchInstructionActive(delivery.status())) throw error("DELIVERY_NOT_ACTIVE");

        IdempotencyRow previous = idempotency(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), PUBLISH_OPERATION, request.idempotencyKey());
        if (previous != null) {
            ensureHash(previous.requestHash(), request.requestHash());
            return findPublishedRevision(request, previous.resourceId());
        }

        if (delivery.version() != request.expectedDeliveryVersion()) throw error("CONCURRENCY_CONFLICT");
        UUID instructionId = request.instructionId() == null ? UUID.randomUUID() : request.instructionId();
        Long currentVersion = jdbc.queryForObject("select max(instruction_version) from logistics.delivery_instruction_revision "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and instruction_id=?",
                Long.class, request.tenantId(), request.workspaceId(), request.deliveryId(), instructionId);
        long nextInstructionVersion;
        if (currentVersion == null) {
            if (request.instructionId() != null) throw error("DELIVERY_INSTRUCTION_NOT_FOUND");
            nextInstructionVersion = 1;
        } else {
            String source = jdbc.queryForObject("select source_kind from logistics.delivery_instruction_revision "
                            + "where tenant_id=? and workspace_id=? and delivery_id=? and instruction_id=? order by instruction_version desc limit 1",
                    String.class,request.tenantId(),request.workspaceId(),request.deliveryId(),instructionId);
            if (!"OPERATIONAL_DISPATCH".equals(source)) throw error("CUSTOMER_INSTRUCTION_EDIT_WINDOW_CLOSED");
            nextInstructionVersion = currentVersion + 1;
        }

        Instant publishedAt = request.publishedAt() == null ? Instant.now() : request.publishedAt();
        UUID revisionId = UUID.randomUUID();
        long nextDeliveryVersion = delivery.version() + 1;
        long nextInstructionSetVersion = delivery.instructionSetVersion() + 1;
        jdbc.update("insert into logistics.delivery_instruction_revision(revision_id,tenant_id,workspace_id,delivery_id,"
                        + "instruction_id,instruction_version,kind,content,authored_by_membership_id,authored_at,request_hash,"
                        + "published_delivery_version,instruction_set_version) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                revisionId, request.tenantId(), request.workspaceId(), request.deliveryId(), instructionId,
                nextInstructionVersion, request.kind().name(), request.content(), request.actorMembershipId(),
                Timestamp.from(publishedAt), request.requestHash(), nextDeliveryVersion, nextInstructionSetVersion);
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1,instruction_set_version=instruction_set_version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and instruction_set_version=?",
                Timestamp.from(publishedAt), request.tenantId(), request.workspaceId(), request.deliveryId(),
                delivery.version(), delivery.instructionSetVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                PUBLISH_OPERATION, request.idempotencyKey(), request.requestHash(), revisionId, publishedAt);
        return new PublishedInstruction(request.deliveryId(), instructionId, request.kind(), request.content(),
                nextInstructionVersion, request.kind().isCritical(), nextDeliveryVersion,
                nextInstructionSetVersion, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AcknowledgementResult acknowledge(AcknowledgeRequest request) {
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                ACK_OPERATION, request.idempotencyKey());
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null || !isDriverInstructionActive(delivery.status())
                || !isAssigned(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.actorMembershipId())) {
            throw error("DELIVERY_NOT_FOUND");
        }

        IdempotencyRow previous = idempotency(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), ACK_OPERATION, request.idempotencyKey());
        if (previous != null) {
            ensureHash(previous.requestHash(), request.requestHash());
            return replayAcknowledgements(request);
        }
        if (delivery.instructionSetVersion() != request.expectedInstructionSetVersion()) {
            throw error("CONCURRENCY_CONFLICT");
        }

        List<AcknowledgementView> acknowledgements = new ArrayList<>(request.instructionIds().size());
        for (UUID instructionId : request.instructionIds()) {
            RevisionRow revision = currentRevision(request.tenantId(), request.workspaceId(),
                    request.deliveryId(), instructionId);
            if (revision == null) throw error("DELIVERY_INSTRUCTION_NOT_FOUND");
            if (!revision.kind().isCritical()) throw error("DELIVERY_INSTRUCTION_NOT_CRITICAL");
            Instant acknowledgedAt = request.acknowledgedAt() == null ? Instant.now() : request.acknowledgedAt();
            jdbc.update("insert into logistics.delivery_instruction_acknowledgement(acknowledgement_id,tenant_id,workspace_id,"
                            + "delivery_id,instruction_id,instruction_version,instruction_set_version,kind_snapshot,content_snapshot,"
                            + "acknowledged_by_membership_id,acknowledged_at,idempotency_key,request_hash) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.deliveryId(), instructionId,
                    revision.instructionVersion(), request.expectedInstructionSetVersion(), revision.kind().name(),
                    revision.content(), request.actorMembershipId(), Timestamp.from(acknowledgedAt),
                    request.idempotencyKey(), request.requestHash());
            acknowledgements.add(new AcknowledgementView(instructionId, revision.instructionVersion(),
                    request.actorMembershipId(), acknowledgedAt));
        }
        Instant commandAt = request.acknowledgedAt() == null ? Instant.now() : request.acknowledgedAt();
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), ACK_OPERATION,
                request.idempotencyKey(), request.requestHash(), request.deliveryId(), commandAt);
        return new AcknowledgementResult(request.deliveryId(), request.expectedInstructionSetVersion(),
                acknowledgements, false);
    }

    private AcknowledgementResult replayAcknowledgements(AcknowledgeRequest request) {
        List<AcknowledgementRow> rows = jdbc.query("select instruction_id,instruction_version,instruction_set_version,"
                        + "acknowledged_by_membership_id,acknowledged_at from logistics.delivery_instruction_acknowledgement "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and acknowledged_by_membership_id=? "
                        + "and idempotency_key=? order by instruction_id",
                (rs, row) -> new AcknowledgementRow(rs.getObject("instruction_id", UUID.class),
                        rs.getLong("instruction_version"), rs.getLong("instruction_set_version"),
                        rs.getObject("acknowledged_by_membership_id", UUID.class), rs.getTimestamp("acknowledged_at").toInstant()),
                request.tenantId(), request.workspaceId(), request.deliveryId(), request.actorMembershipId(),
                request.idempotencyKey());
        if (rows.isEmpty()) throw error("DELIVERY_INSTRUCTION_ACKNOWLEDGEMENT_NOT_FOUND");
        return new AcknowledgementResult(request.deliveryId(), rows.getFirst().instructionSetVersion(),
                rows.stream().map(row -> new AcknowledgementView(row.instructionId(), row.instructionVersion(),
                        row.actorMembershipId(), row.acknowledgedAt())).toList(), true);
    }

    private RevisionRow currentRevision(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID instructionId) {
        return jdbc.query("select instruction_version,kind,content from logistics.delivery_instruction_revision "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and instruction_id=? "
                        + "order by instruction_version desc limit 1",
                (rs, row) -> new RevisionRow(rs.getLong("instruction_version"),
                        DeliveryInstructionKind.valueOf(rs.getString("kind")), rs.getString("content")),
                tenantId, workspaceId, deliveryId, instructionId).stream().findFirst().orElse(null);
    }

    private PublishedInstruction findPublishedRevision(PublishRequest request, UUID revisionId) {
        return jdbc.query("select instruction_id,kind,content,instruction_version,published_delivery_version,"
                        + "instruction_set_version from logistics.delivery_instruction_revision "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and revision_id=?",
                (rs, row) -> {
                    DeliveryInstructionKind kind = DeliveryInstructionKind.valueOf(rs.getString("kind"));
                    return new PublishedInstruction(request.deliveryId(), rs.getObject("instruction_id", UUID.class),
                            kind, rs.getString("content"), rs.getLong("instruction_version"), kind.isCritical(),
                            rs.getLong("published_delivery_version"), rs.getLong("instruction_set_version"), true);
                }, request.tenantId(), request.workspaceId(), request.deliveryId(), revisionId)
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_INSTRUCTION_NOT_FOUND"));
    }

    private DeliveryRow lockDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select id,status,version,instruction_set_version,fulfillment_id from logistics.delivery "
                        + "where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version"), rs.getLong("instruction_set_version"),
                        rs.getObject("fulfillment_id", UUID.class)), tenantId, workspaceId, deliveryId)
                .stream().findFirst().orElse(null);
    }

    private boolean isAssigned(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_assignment "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and responsible_membership_id=?)",
                Boolean.class, tenantId, workspaceId, deliveryId, membershipId));
    }

    private void lockCommand(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                             String operation, String idempotencyKey) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenantId + "|" + workspaceId + "|" + operation + "|" + actorMembershipId + "|" + idempotencyKey);
    }

    private IdempotencyRow idempotency(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                       String operation, String idempotencyKey) {
        return jdbc.query("select request_hash,resource_id from logistics.delivery_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                tenantId, workspaceId, actorMembershipId, operation, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    private void insertIdempotency(UUID tenantId, UUID workspaceId, UUID actorMembershipId, String operation,
                                   String idempotencyKey, String requestHash, UUID resourceId, Instant createdAt) {
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,"
                        + "operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                tenantId, workspaceId, actorMembershipId, operation, idempotencyKey, requestHash,
                resourceId, Timestamp.from(createdAt));
    }

    private static boolean isDriverInstructionActive(String status) {
        return "PLANNED".equals(status) || "ASSIGNED".equals(status)
                || "DISPATCHED".equals(status) || "IN_TRANSIT".equals(status);
    }

    private static boolean isDispatchInstructionActive(String status) {
        return "PLANNED".equals(status) || isDriverInstructionActive(status);
    }

    private static void ensureHash(String stored, String actual) {
        if (!Objects.equals(stored, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code, code.endsWith("_NOT_FOUND") || "DELIVERY_NOT_FOUND".equals(code));
    }

    private record InstructionSetRow(UUID deliveryId, long deliveryVersion, long instructionSetVersion,
                                     InstructionView instruction) { }
    private record DeliveryRow(UUID id, String status, long version, long instructionSetVersion, UUID fulfillmentId) { }
    private record RevisionRow(long instructionVersion, DeliveryInstructionKind kind, String content) { }
    private record AcknowledgementRow(UUID instructionId, long instructionVersion, long instructionSetVersion,
                                      UUID actorMembershipId, Instant acknowledgedAt) { }
    private record IdempotencyRow(String requestHash, UUID resourceId) { }
}
