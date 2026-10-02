package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.DriverTrackingPort;
import com.nexa.api.fulfillmentdelivery.domain.tracking.DriverLocationPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import static com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService.error;

@Repository
@Profile("!test")
public class JdbcDriverTrackingAdapter implements DriverTrackingPort {
 private final JdbcTemplate jdbc;
 public JdbcDriverTrackingAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Override public Workday current(UUID t,UUID w,UUID a){
  return jdbc.query("select * from logistics.driver_workday where tenant_id=? and workspace_id=? and actor_membership_id=? and status<>'CLOSED'",(rs,n)->day(rs),t,w,a).stream().findFirst().orElse(null);
 }
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public Workday command(WorkdayCommand r){
  Instant now=r.now().truncatedTo(ChronoUnit.MICROS);
  jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.ResultSetExtractor<Void>)rs->null,r.tenantId()+"|"+r.workspaceId()+"|driver-workday|"+r.actorMembershipId());
  var previous=jdbc.query("select * from logistics.driver_workday_event where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?",(rs,n)->{
   if(!r.requestHash().equals(rs.getString("request_hash")))throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false);
   return new Workday(rs.getObject("workday_id",UUID.class),rs.getLong("workday_version"),rs.getString("status"),rs.getTimestamp("started_at").toInstant(),instant(rs,"ended_at"),"ACTIVE".equals(rs.getString("status")));
  },r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.idempotencyKey());
  if(!previous.isEmpty())return previous.getFirst();
  Workday day;
  if("START".equals(r.action())){
   if(current(r.tenantId(),r.workspaceId(),r.actorMembershipId())!=null)throw error("DRIVER_WORKDAY_ALREADY_ACTIVE",false);
   day=new Workday(UUID.randomUUID(),0,"ACTIVE",now,null,true);
   jdbc.update("insert into logistics.driver_workday(id,tenant_id,workspace_id,actor_membership_id,version,status,started_at) values(?,?,?,?,0,'ACTIVE',?)",day.id(),r.tenantId(),r.workspaceId(),r.actorMembershipId(),Timestamp.from(now));
  }else{
   Workday old=lockDay(r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.workdayId());
   if(old==null || "CLOSED".equals(old.status()))throw error("DRIVER_WORKDAY_NOT_FOUND",true);
   if(old.version()!=r.expectedVersion())throw error("CONCURRENCY_CONFLICT",false);
   String status="END".equals(r.action())?"CLOSED":("AVAILABLE".equals(r.action())?"ACTIVE":"LOCATION_UNAVAILABLE");
   day=new Workday(old.id(),old.version()+1,status,old.startedAt(),"CLOSED".equals(status)?now:null,"ACTIVE".equals(status));
   jdbc.update("update logistics.driver_workday set version=version+1,status=?,ended_at=? where tenant_id=? and workspace_id=? and id=?",status,ts(day.endedAt()),r.tenantId(),r.workspaceId(),day.id());
  }
  jdbc.update("insert into logistics.driver_workday_event(id,tenant_id,workspace_id,actor_membership_id,workday_id,workday_version,action,status,started_at,ended_at,occurred_at,idempotency_key,request_hash) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),r.tenantId(),r.workspaceId(),r.actorMembershipId(),day.id(),day.version(),r.action(),day.status(),ts(day.startedAt()),ts(day.endedAt()),ts(now),r.idempotencyKey(),r.requestHash());
  return day;
 }
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public Coordinate capture(SampleCommand r){
  Workday day=lockDay(r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.workdayId());
  if(day==null || !"ACTIVE".equals(day.status()))throw error("DRIVER_LOCATION_UNAVAILABLE",false);
  Coordinate c=r.coordinate();
  if(!DriverLocationPolicy.valid(c.latitude(),c.longitude(),c.accuracyMeters(),c.capturedAt(),day.startedAt(),r.now()))throw error("DRIVER_COORDINATE_INVALID",false);
  String requestHash=coordinateHash(r);
  var old=jdbc.query("select * from logistics.driver_coordinate where tenant_id=? and workspace_id=? and sample_id=? for update",
          (rs,n)->new StoredCoordinate(coordinate(rs),rs.getString("request_hash"),rs.getObject("actor_membership_id",UUID.class),rs.getObject("workday_id",UUID.class)),
          r.tenantId(),r.workspaceId(),c.sampleId());
  if(!old.isEmpty()){
   StoredCoordinate stored=old.getFirst();
   Coordinate prior=stored.coordinate();
   boolean sameActor=stored.actor().equals(r.actorMembershipId()) && stored.workday().equals(r.workdayId());
   boolean samePayload=stored.requestHash()!=null ? stored.requestHash().equals(requestHash)
           : prior.latitude()==c.latitude() && prior.longitude()==c.longitude() && prior.accuracyMeters()==c.accuracyMeters() && prior.capturedAt().equals(c.capturedAt());
   if(!sameActor || !samePayload)throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false);
   return prior;
  }
  Instant capturedAt=c.capturedAt().truncatedTo(ChronoUnit.MICROS);
  Instant expiry=capturedAt.plus(DriverLocationPolicy.MAX_RETENTION);
  jdbc.update("insert into logistics.driver_coordinate(sample_id,tenant_id,workspace_id,actor_membership_id,workday_id,latitude,longitude,accuracy_meters,captured_at,received_at,expires_at,request_hash) values(?,?,?,?,?,?,?,?,?,?,?,?)",
          c.sampleId(),r.tenantId(),r.workspaceId(),r.actorMembershipId(),r.workdayId(),c.latitude(),c.longitude(),c.accuracyMeters(),ts(capturedAt),ts(r.now().truncatedTo(ChronoUnit.MICROS)),ts(expiry),requestHash);
  return new Coordinate(c.sampleId(),c.latitude(),c.longitude(),c.accuracyMeters(),capturedAt,expiry);
 }
 @Override public Coordinate latest(UUID t,UUID w,UUID a,Instant now){
  return jdbc.query("select p.* from logistics.driver_coordinate p join logistics.driver_workday d on d.tenant_id=p.tenant_id and d.workspace_id=p.workspace_id and d.id=p.workday_id where p.tenant_id=? and p.workspace_id=? and p.actor_membership_id=? and d.status='ACTIVE' and p.expires_at>? order by p.captured_at desc,p.received_at desc limit 1",(rs,n)->coordinate(rs),t,w,a,ts(now)).stream().findFirst().orElse(null);
 }
 @Override public DeliveryScope deliveryScope(UUID t,UUID w,UUID id){
  return jdbc.query("select coalesce(f.sales_order_id,o.sales_order_id) sales_order_id,a.responsible_membership_id,d.status from logistics.delivery d left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id left join logistics.dispatch_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.dispatch_order_id join logistics.delivery_assignment a on a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.delivery_id=d.id where d.tenant_id=? and d.workspace_id=? and d.id=?",(rs,n)->new DeliveryScope(rs.getObject("sales_order_id",UUID.class),rs.getObject("responsible_membership_id",UUID.class),rs.getString("status")),t,w,id).stream().findFirst().orElse(null);
 }
 @Override @Scheduled(fixedDelay=30000) public void purgeExpiredCoordinates(){jdbc.execute("select logistics.purge_expired_driver_coordinates()");}
 private Workday lockDay(UUID t,UUID w,UUID a,UUID id){return jdbc.query("select * from logistics.driver_workday where tenant_id=? and workspace_id=? and actor_membership_id=? and id=? for update",(rs,n)->day(rs),t,w,a,id).stream().findFirst().orElse(null);}
 private static Workday day(ResultSet rs)throws SQLException{return new Workday(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getString("status"),rs.getTimestamp("started_at").toInstant(),instant(rs,"ended_at"),"ACTIVE".equals(rs.getString("status")));}
 private static Coordinate coordinate(ResultSet rs)throws SQLException{return new Coordinate(rs.getObject("sample_id",UUID.class),rs.getDouble("latitude"),rs.getDouble("longitude"),rs.getDouble("accuracy_meters"),rs.getTimestamp("captured_at").toInstant(),rs.getTimestamp("expires_at").toInstant());}
 private record StoredCoordinate(Coordinate coordinate,String requestHash,UUID actor,UUID workday) { }
 private static String coordinateHash(SampleCommand request){
  Coordinate c=request.coordinate();
  String payload=request.tenantId()+"|"+request.workspaceId()+"|"+request.actorMembershipId()+"|"+request.workdayId()+"|"+c.sampleId()+"|"+c.latitude()+"|"+c.longitude()+"|"+c.accuracyMeters()+"|"+c.capturedAt();
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
 }
 private static Instant instant(ResultSet rs,String field)throws SQLException{var t=rs.getTimestamp(field);return t==null?null:t.toInstant();}
 private static Timestamp ts(Instant value){return value==null?null:Timestamp.from(value);}
}
