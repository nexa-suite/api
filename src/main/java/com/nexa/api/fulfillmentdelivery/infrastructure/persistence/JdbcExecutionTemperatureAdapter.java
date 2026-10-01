package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.ExecutionTemperaturePort;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcExecutionTemperatureAdapter implements ExecutionTemperaturePort {
    private final JdbcTemplate jdbc;
    public JdbcExecutionTemperatureAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override
    public Delivery delivery(Scope s,boolean driver,boolean lock) {
        String warehouse="(select min(p.warehouse_id::text)::uuid from logistics.picking_result_line p "
                + "join logistics.fulfillment_line l on l.tenant_id=p.tenant_id and l.workspace_id=p.workspace_id "
                + "and l.id=p.fulfillment_line_id where l.tenant_id=d.tenant_id and l.workspace_id=d.workspace_id "
                + "and l.fulfillment_id=d.fulfillment_id having count(distinct p.warehouse_id)=1) warehouse_id";
        return jdbc.query("select d.id,d.fulfillment_id,d.version,d.status,"+warehouse+","
                + "(select a.id from logistics.delivery_active_attempt a where a.tenant_id=d.tenant_id "
                + "and a.workspace_id=d.workspace_id and a.delivery_id=d.id) attempt_id "
                + "from logistics.delivery d where d.tenant_id=? and d.workspace_id=? and d.id=? "
                + (driver ? "and exists(select 1 from logistics.delivery_assignment a where a.tenant_id=d.tenant_id "
                    + "and a.workspace_id=d.workspace_id and a.delivery_id=d.id and a.responsible_membership_id=?) " : "")
                + (lock ? "for update of d" : ""), (rs,n)->new Delivery(rs.getObject("id",UUID.class),
                    rs.getObject("fulfillment_id",UUID.class),rs.getObject("warehouse_id",UUID.class),rs.getLong("version"),
                    rs.getString("status"),rs.getObject("attempt_id",UUID.class)),
                driver ? new Object[]{s.tenantId(),s.workspaceId(),s.deliveryId(),s.actorMembershipId()}
                       : new Object[]{s.tenantId(),s.workspaceId(),s.deliveryId()})
                .stream().filter(d->d.fulfillmentId()!=null).findFirst().orElseThrow(()->error("DELIVERY_NOT_FOUND",true));
    }
    @Override
    public List<RawLine> lines(Scope s,UUID fulfillment) {
        return jdbc.query("select id,sku_id,unit,greatest(dispatched_quantity-delivered_quantity-rejected_quantity-cancelled_quantity,0) remaining "
                + "from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? order by id",
                (rs,n)->new RawLine(rs.getObject("id",UUID.class),rs.getObject("sku_id",UUID.class),rs.getString("unit"),rs.getBigDecimal("remaining")),
                s.tenantId(),s.workspaceId(),fulfillment);
    }
    @Override
    public List<Hold> holds(Scope s) {
        return jdbc.query("select h.*,e.fulfillment_line_id,e.sku_id,e.affected_quantity,e.quantity_unit,e.actor_membership_id reporter,e.recorded_at,"
                + "x.disposition,x.actor_membership_id authorizer,x.occurred_at disposed_at,x.reason "
                + "from logistics.delivery_execution_hold h join logistics.delivery_execution_temperature_evidence e "
                + "on e.tenant_id=h.tenant_id and e.workspace_id=h.workspace_id and e.id=h.reading_id "
                + "left join lateral(select * from logistics.delivery_execution_disposition x where x.tenant_id=h.tenant_id "
                + "and x.workspace_id=h.workspace_id and x.hold_id=h.id order by x.sequence desc limit 1)x on true "
                + "where h.tenant_id=? and h.workspace_id=? and h.delivery_id=? order by e.recorded_at,h.id",
                (rs,n)->new Hold(rs.getObject("id",UUID.class),rs.getObject("reading_id",UUID.class),rs.getObject("exception_id",UUID.class),
                    rs.getObject("fulfillment_line_id",UUID.class),rs.getObject("sku_id",UUID.class),rs.getBigDecimal("affected_quantity"),
                    rs.getString("quantity_unit"),status(rs.getString("disposition")),rs.getObject("reporter",UUID.class),
                    rs.getTimestamp("recorded_at").toInstant(),rs.getString("disposition")==null?null:Disposition.valueOf(rs.getString("disposition")),
                    rs.getObject("authorizer",UUID.class),rs.getTimestamp("disposed_at")==null?null:rs.getTimestamp("disposed_at").toInstant(),
                    rs.getString("reason")),s.tenantId(),s.workspaceId(),s.deliveryId());
    }
    @Override
    public Reading replay(Scope s,String operation,String key,String hash) {
        lockCommand(s,operation,key);
        var prior=jdbc.query("select resource_id,request_hash from logistics.delivery_command_idempotency "
                + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?",
                (rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)},s.tenantId(),s.workspaceId(),s.actorMembershipId(),operation,key);
        if(prior.isEmpty()) return null;
        if(!hash.equals(prior.getFirst()[1])) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false);
        return reading(s,(UUID)prior.getFirst()[0],true);
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Reading record(Scope s,Delivery d,long version,String key,String hash,ReadingCommand c,
                          BigDecimal minimum,BigDecimal maximum,boolean excursion,Instant now) {
        RawLine line=lines(s,d.fulfillmentId()).stream().filter(l->l.id().equals(c.fulfillmentLineId())).findFirst().orElseThrow(()->error("DELIVERY_OUTCOME_LINE_INVALID",false));
        UUID exceptionId=null;
        if(c.sourceIncidentId()!=null) {
            exceptionId=jdbc.query("select id from logistics.operational_exception_case where tenant_id=? and workspace_id=? "
                    + "and delivery_id=? and source_kind='DRIVER_INCIDENT' and source_driver_incident_id=? "
                    + "and reported_by_membership_id=? and type='TEMPERATURE_EXCURSION' and severity='CRITICAL'",
                    (rs,n)->rs.getObject(1,UUID.class),s.tenantId(),s.workspaceId(),s.deliveryId(),c.sourceIncidentId(),s.actorMembershipId())
                    .stream().findFirst().orElseThrow(()->error("OPERATIONAL_EXCEPTION_NOT_FOUND",true));
            boolean existing=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_execution_hold "
                    + "where tenant_id=? and workspace_id=? and exception_id=?)",Boolean.class,s.tenantId(),s.workspaceId(),exceptionId));
            if(excursion && existing) throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID",false);
        }
        UUID id=UUID.randomUUID(); long next=version+1;
        jdbc.update("insert into logistics.delivery_execution_temperature_evidence(id,tenant_id,workspace_id,delivery_id,attempt_id,fulfillment_line_id,sku_id,affected_quantity,quantity_unit,value_celsius,minimum_celsius,maximum_celsius,status,actor_membership_id,occurred_at,recorded_at,evidence_object_id,source_incident_id,delivery_version) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,s.tenantId(),s.workspaceId(),s.deliveryId(),d.attemptId(),c.fulfillmentLineId(),c.skuId(),c.affectedQuantity(),line.unit(),
                c.value(),minimum,maximum,excursion?"OUT_OF_RANGE":"WITHIN_RANGE",s.actorMembershipId(),Timestamp.from(c.occurredAt()),Timestamp.from(now),c.evidenceObjectId(),c.sourceIncidentId(),next);
        if(excursion) jdbc.update("insert into logistics.delivery_execution_hold(id,tenant_id,workspace_id,delivery_id,reading_id,exception_id) values(?,?,?,?,?,?)",
                UUID.randomUUID(),s.tenantId(),s.workspaceId(),s.deliveryId(),id,exceptionId);
        advance(s,version,now); remember(s,"EXECUTION_TEMPERATURE",key,hash,id,now);
        return reading(s,id,false);
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public DispositionResult dispose(Scope s,Delivery d,UUID holdId,long version,String key,String hash,Disposition disposition,String reason,Instant now) {
        lockCommand(s,"EXECUTION_DISPOSITION",key);
        var prior=jdbc.query("select resource_id,request_hash from logistics.delivery_command_idempotency where tenant_id=? "
                + "and workspace_id=? and actor_membership_id=? and operation='EXECUTION_DISPOSITION' and idempotency_key=?",
                (rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)},s.tenantId(),s.workspaceId(),s.actorMembershipId(),key);
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.getFirst()[1])) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false);
            return dispositionSnapshot(s,(UUID)prior.getFirst()[0],true);
        }
        if(d.version()!=version) throw error("CONCURRENCY_CONFLICT",false);
        Hold h=holds(s).stream().filter(v->v.id().equals(holdId)).findFirst().orElseThrow(()->error("OPERATIONAL_EXCEPTION_NOT_FOUND",true));
        if(!"HELD".equals(h.status())) throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID",false);
        int sequence=jdbc.queryForObject("select coalesce(max(sequence),0)+1 from logistics.delivery_execution_disposition where tenant_id=? and workspace_id=? and hold_id=?",
                Integer.class,s.tenantId(),s.workspaceId(),holdId);
        UUID id=UUID.randomUUID();
        jdbc.update("insert into logistics.delivery_execution_disposition(id,tenant_id,workspace_id,delivery_id,hold_id,sequence,disposition,actor_membership_id,occurred_at,reason,delivery_version) values(?,?,?,?,?,?,?,?,?,?,?)",
                id,s.tenantId(),s.workspaceId(),s.deliveryId(),holdId,sequence,disposition.name(),s.actorMembershipId(),Timestamp.from(now),reason,version+1);
        advance(s,version,now); remember(s,"EXECUTION_DISPOSITION",key,hash,id,now);
        return dispositionSnapshot(s,id,false);
    }
    private DispositionResult dispositionSnapshot(Scope s,UUID id,boolean replayed) {
        return jdbc.query("select hold_id,disposition,actor_membership_id,occurred_at,reason,delivery_version from logistics.delivery_execution_disposition "
                + "where tenant_id=? and workspace_id=? and delivery_id=? and id=?",(rs,n)->{
            UUID holdId=rs.getObject("hold_id",UUID.class);
            Hold h=holds(s).stream().filter(v->v.id().equals(holdId)).findFirst().orElseThrow();
            Disposition disposition=Disposition.valueOf(rs.getString("disposition"));
            return new DispositionResult(new Hold(h.id(),h.readingId(),h.exceptionId(),h.fulfillmentLineId(),h.skuId(),h.affectedQuantity(),h.quantityUnit(),status(disposition.name()),
                    h.reportedByMembershipId(),h.reportedAt(),disposition,rs.getObject("actor_membership_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),rs.getString("reason")),rs.getLong("delivery_version"),replayed);
        },s.tenantId(),s.workspaceId(),s.deliveryId(),id).stream().findFirst().orElseThrow(()->error("OPERATIONAL_EXCEPTION_NOT_FOUND",true));
    }
    private Reading reading(Scope s,UUID id,boolean replayed) {
        Hold h=holds(s).stream().filter(v->v.readingId().equals(id)).findFirst().orElse(null);
        return jdbc.query("select * from logistics.delivery_execution_temperature_evidence where tenant_id=? and workspace_id=? and delivery_id=? and id=?",
                (rs,n)->new Reading(id,s.deliveryId(),rs.getObject("attempt_id",UUID.class),rs.getObject("fulfillment_line_id",UUID.class),rs.getObject("sku_id",UUID.class),
                    rs.getBigDecimal("affected_quantity"),rs.getString("quantity_unit"),rs.getBigDecimal("value_celsius"),"CELSIUS",rs.getBigDecimal("minimum_celsius"),rs.getBigDecimal("maximum_celsius"),
                    rs.getString("status"),rs.getObject("actor_membership_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),rs.getTimestamp("recorded_at").toInstant(),
                    rs.getObject("evidence_object_id",UUID.class),rs.getObject("source_incident_id",UUID.class),h,rs.getLong("delivery_version"),replayed),
                s.tenantId(),s.workspaceId(),s.deliveryId(),id).stream().findFirst().orElseThrow(()->error("DELIVERY_NOT_FOUND",true));
    }
    private void advance(Scope s,long version,Instant now) { if(jdbc.update("update logistics.delivery set version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and version=?",Timestamp.from(now),s.tenantId(),s.workspaceId(),s.deliveryId(),version)!=1) throw error("CONCURRENCY_CONFLICT",false); }
    private void lockCommand(Scope s,String operation,String key) { jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.ResultSetExtractor<Void>) rs->null,s.tenantId()+"|"+s.workspaceId()+"|"+s.actorMembershipId()+"|"+operation+"|"+key); }
    private void remember(Scope s,String operation,String key,String hash,UUID id,Instant now) { jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values(?,?,?,?,?,?,?,?)",s.tenantId(),s.workspaceId(),s.actorMembershipId(),operation,key,hash,id,Timestamp.from(now)); }
    private static String status(String disposition) { return disposition==null || "CONTINUE_HOLD".equals(disposition)?"HELD": switch(disposition) { case "RELEASE"->"RELEASED"; case "REJECT"->"REJECTED"; case "WASTE"->"WASTED"; default->throw new IllegalArgumentException("Unknown disposition"); }; }
    private static FulfillmentOperationException error(String code,boolean hidden) { return new FulfillmentOperationException(code,hidden); }
}
