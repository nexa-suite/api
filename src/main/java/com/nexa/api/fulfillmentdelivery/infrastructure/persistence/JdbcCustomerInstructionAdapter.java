package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.CustomerInstructionModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.CustomerInstructionPort;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcCustomerInstructionAdapter implements CustomerInstructionPort {
    private final JdbcTemplate jdbc;
    public JdbcCustomerInstructionAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public Snapshot read(UUID tenant, UUID workspace, UUID order) {
        Long version = jdbc.query("select version from logistics.customer_instruction_set where tenant_id=? and workspace_id=? and sales_order_id=?",
                (rs,n)->rs.getLong(1),tenant,workspace,order).stream().findFirst().orElse(0L);
        var items = jdbc.query("select distinct on(instruction_id) * from logistics.customer_instruction_revision "
                        + "where tenant_id=? and workspace_id=? and sales_order_id=? order by instruction_id,instruction_version desc",
                (rs,n)->new Instruction(rs.getObject("instruction_id",UUID.class),rs.getLong("instruction_version"),
                        DeliveryInstructionKind.valueOf(rs.getString("kind")),rs.getString("content"),rs.getString("source_kind"),
                        rs.getString("source_reference"),rs.getObject("recorded_by_membership_id",UUID.class),rs.getTimestamp("recorded_at").toInstant()),tenant,workspace,order);
        return new Snapshot(order,version,!windowClosed(tenant,workspace,order),items);
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot publish(Publish r) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                r.tenantId()+"|"+r.workspaceId()+"|CUSTOMER_INSTRUCTION_PUBLISH|"+r.actorMembershipId()+"|"+r.idempotencyKey());
        // The application holds the Sales Commitment row; lock existing Fulfillments in deterministic order.
        jdbc.query("select id from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=? order by id for update",
                (rs,n)->rs.getObject(1,UUID.class),r.tenantId(),r.workspaceId(),r.salesOrderId());
        jdbc.query("select id from logistics.dispatch_order where tenant_id=? and workspace_id=? and sales_order_id=? order by id for update",
                (rs,n)->rs.getObject(1,UUID.class),r.tenantId(),r.workspaceId(),r.salesOrderId());
        jdbc.update("insert into logistics.customer_instruction_set(tenant_id,workspace_id,sales_order_id) values(?,?,?) on conflict do nothing",r.tenantId(),r.workspaceId(),r.salesOrderId());
        long version = jdbc.queryForObject("select version from logistics.customer_instruction_set where tenant_id=? and workspace_id=? and sales_order_id=? for update",
                Long.class,r.tenantId(),r.workspaceId(),r.salesOrderId());
        var prior = jdbc.query("select request_hash from logistics.delivery_command_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='CUSTOMER_INSTRUCTION_PUBLISH' and idempotency_key=?",
                (rs,n)->rs.getString(1),r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.idempotencyKey());
        if (!prior.isEmpty()) {
            if (!prior.getFirst().equals(r.requestHash())) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
            return read(r.tenantId(),r.workspaceId(),r.salesOrderId());
        }
        if (!r.salesOrderActive()) throw error("DELIVERY_NOT_ACTIVE");
        if (version != r.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        if (windowClosed(r.tenantId(),r.workspaceId(),r.salesOrderId())) throw error("CUSTOMER_INSTRUCTION_EDIT_WINDOW_CLOSED");
        UUID instruction = r.instructionId()==null ? UUID.randomUUID() : r.instructionId();
        Long latest = jdbc.queryForObject("select max(instruction_version) from logistics.customer_instruction_revision where tenant_id=? and workspace_id=? and sales_order_id=? and instruction_id=?",
                Long.class,r.tenantId(),r.workspaceId(),r.salesOrderId(),instruction);
        UUID revision = UUID.randomUUID();
        jdbc.update("insert into logistics.customer_instruction_revision(revision_id,tenant_id,workspace_id,sales_order_id,instruction_id,instruction_version,set_version,kind,content,source_kind,source_reference,recorded_by_membership_id,recorded_at,request_hash) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                revision,r.tenantId(),r.workspaceId(),r.salesOrderId(),instruction,latest==null?1:latest+1,version+1,
                r.kind().name(),r.content(),r.sourceKind(),r.sourceReference(),r.actorMembershipId(),Timestamp.from(r.recordedAt()),r.requestHash());
        jdbc.update("update logistics.customer_instruction_set set version=version+1 where tenant_id=? and workspace_id=? and sales_order_id=?",r.tenantId(),r.workspaceId(),r.salesOrderId());
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values(?,?,?,'CUSTOMER_INSTRUCTION_PUBLISH',?,?,?,?)",
                r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.idempotencyKey(),r.requestHash(),revision,Timestamp.from(r.recordedAt()));
        var planned = jdbc.query("select d.id from logistics.delivery d join logistics.dispatch_order o "
                        + "on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.dispatch_order_id "
                        + "where d.tenant_id=? and d.workspace_id=? and o.sales_order_id=? and d.status='PLANNED' order by d.id",
                (rs,n)->rs.getObject(1,UUID.class),r.tenantId(),r.workspaceId(),r.salesOrderId());
        for (UUID delivery : planned) CustomerInstructionDeliveryProjection.copy(jdbc,r.tenantId(),r.workspaceId(),r.salesOrderId(),delivery,r.recordedAt());
        return read(r.tenantId(),r.workspaceId(),r.salesOrderId());
    }
    private boolean windowClosed(UUID t, UUID w, UUID order) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=? and status in('READY_FOR_DISPATCH','HANDED_OVER','COMPLETED')) "
                        + "or exists(select 1 from logistics.dispatch_order where tenant_id=? and workspace_id=? and sales_order_id=? and status in('READY_FOR_ROUTE','IN_ROUTE','PARTIAL','DELIVERED','INCIDENT','REPROGRAMMED','CANCELLED'))"
                        + "or exists(select 1 from logistics.fulfillment f join logistics.fulfillment_event e "
                        + "on e.tenant_id=f.tenant_id and e.workspace_id=f.workspace_id and e.fulfillment_id=f.id "
                        + "where f.tenant_id=? and f.workspace_id=? and f.sales_order_id=? and e.to_status='READY_FOR_DISPATCH')",
                Boolean.class,t,w,order,t,w,order,t,w,order));
    }
    private static FulfillmentOperationException error(String code) { return new FulfillmentOperationException(code,false); }
}
