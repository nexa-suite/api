package com.nexa.api.logistics.presentation;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.LogisticsOperationsService.LogisticsException;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Logistics")
@SecurityRequirement(name = "bearerAuth")
public final class LogisticsController {
    private static final String ACCESS = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final LogisticsOperationsService service;
    public LogisticsController(LogisticsOperationsService service) { this.service = service; }

    @GetMapping("/dispatch-orders") public PageResponse<DispatchResponse> dispatches(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size, @RequestParam(defaultValue="updatedAt,desc") String sort) { return page(service.list(c,status,page,size,sort)); }
    @GetMapping("/dispatch-assignees") @Operation(operationId = "listDispatchAssignees") public List<LogisticsOperationsService.AssigneeView> assignees(@RequestAttribute(ACCESS) CurrentAccessContext c) { return service.assignees(c); }
    @GetMapping("/dispatch-orders/{id}") public ResponseEntity<DispatchResponse> dispatch(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){LogisticsOperationsService.DispatchView value=service.detail(c,id);return ResponseEntity.ok().eTag(etag(value.version())).body(response(value));}
    @GetMapping("/dispatch-orders/{id}/events") public List<DispatchEventResponse> events(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return service.events(c,id).stream().map(this::event).toList();}
    @GetMapping("/dispatch-orders/{id}/handoff-notes") public List<HandoffNoteResponse> handoffNotes(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return service.handoffNotes(c,id).stream().map(this::handoffNote).toList();}
    @PostMapping("/inventory-reservations/{reservationId}/dispatch-orders") @Operation(operationId = "createDispatchOrder") public ResponseEntity<DispatchResponse> create(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String reservationId,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key){return created(response(service.create(c,reservationId,version(ifMatch),key)));}
    @PostMapping("/dispatch-orders/{id}/preparation-starts") public ResponseEntity<DispatchResponse> prepare(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key){return mutation(service.prepare(c,id,version(ifMatch),key));}
    @PostMapping("/dispatch-orders/{id}/assignments") public ResponseEntity<DispatchResponse> assign(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody AssignmentRequest r){return mutation(service.assign(c,id,version(ifMatch),key,r.responsibleMembershipId(),r.vehicleReference(),r.routeName()));}
    @PostMapping("/dispatch-orders/{id}/schedules") public ResponseEntity<DispatchResponse> schedule(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ScheduleRequest r){return mutation(service.schedule(c,id,version(ifMatch),key,r.deliveryWindowStart(),r.deliveryWindowEnd(),r.eta()));}
    @PostMapping("/dispatch-orders/{id}/route-readiness") public ResponseEntity<DispatchResponse> ready(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key){return mutation(service.ready(c,id,version(ifMatch),key));}
    @PostMapping("/dispatch-orders/{id}/route-starts") public ResponseEntity<DispatchResponse> routeStart(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key){return mutation(service.startRoute(c,id,version(ifMatch),key));}
    @PostMapping("/dispatch-orders/{id}/temperature-readings") public ResponseEntity<DispatchResponse> temperature(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody TemperatureRequest r){return mutation(service.temperature(c,id,version(ifMatch),key,r.value(),r.unit(),r.recordedAt(),r.source()));}
    @PostMapping("/dispatch-orders/{id}/incidents") public ResponseEntity<DispatchResponse> incident(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody IncidentRequest r){return mutation(service.incident(c,id,version(ifMatch),key,r.type(),r.severity(),r.buyerVisible(),r.description(),r.occurredAt(),r.resolution()));}
    @PostMapping("/dispatch-orders/{id}/reprogrammings") public ResponseEntity<DispatchResponse> reprogram(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ReprogramRequest r){return mutation(service.reprogram(c,id,version(ifMatch),key,r.deliveryWindowStart(),r.deliveryWindowEnd(),r.eta(),r.reason()));}
    @PostMapping("/dispatch-orders/{id}/cancellations") @Operation(operationId = "cancelDispatchOrder") public ResponseEntity<DispatchResponse> cancel(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody(required=false) ReasonRequest r){return mutation(service.cancel(c,id,version(ifMatch),key,r==null?null:r.reason()));}
    @PostMapping("/dispatch-orders/{id}/delivery-completions") public ResponseEntity<DispatchResponse> complete(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PodRequest r){return mutation(service.complete(c,id,version(ifMatch),key,r.receiverName(),r.completedAt(),r.notes(),r.photoEvidenceDeclared(),r.signatureEvidenceDeclared()));}
    @PostMapping("/dispatch-orders/{id}/handoff-notes") public ResponseEntity<HandoffNoteResponse> appendHandoffNote(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody HandoffNoteRequest r){HandoffNoteResponse value=handoffNote(service.appendHandoffNote(c,id,version(ifMatch),key,r.note()));return ResponseEntity.status(201).eTag(etag(value.dispatchVersion())).body(value);}
    @GetMapping("/proof-of-delivery") public PageResponse<PodResponse> proof(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return podPage(service.proofOfDelivery(c,status,page,size));}
    @GetMapping("/logistics/operations-dashboard") public LogisticsOperationsService.DashboardView dashboard(@RequestAttribute(ACCESS) CurrentAccessContext c){return service.dashboard(c);}
    @GetMapping("/logistics/operational-analytics") public LogisticsOperationsService.AnalyticsView analytics(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){return service.analytics(c,from,to);}
    @GetMapping("/my-deliveries") public PageResponse<DispatchResponse> myDeliveries(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="updatedAt,desc") String sort){return page(service.list(c,status,page,size,sort));}
    @GetMapping("/my-deliveries/{id}") public ResponseEntity<DispatchResponse> myDelivery(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){LogisticsOperationsService.DispatchView value=service.detail(c,id);return ResponseEntity.ok().eTag(etag(value.version())).body(response(value));}
    @GetMapping("/my-deliveries/{id}/events") public List<DispatchEventResponse> myDeliveryEvents(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return events(c,id);}

    private PageResponse<DispatchResponse> page(LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> p){return new PageResponse<>(p.items().stream().map(this::response).toList(),p.page(),p.size(),p.total());}
    private PageResponse<PodResponse> podPage(LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> p){return new PageResponse<>(p.items().stream().map(x->new PodResponse(x.podId(),x.dispatchOrderId(),x.dispatchNumber(),x.status(),x.receiverName(),x.completedAt(),x.notes(),x.photoEvidenceDeclared(),x.signatureEvidenceDeclared(),x.updatedAt())).toList(),p.page(),p.size(),p.total());}
    private DispatchResponse response(LogisticsOperationsService.DispatchView x){LogisticsOperationsService.AssignmentView a=x.assignment();return new DispatchResponse(x.id(),x.dispatchNumber(),x.reservationId(),x.salesOrderId(),x.salesOrderNumber(),x.clientAccountId(),x.clientCode(),x.clientName(),x.status(),x.destination(),x.deliveryArea(),x.priority(),x.deliveryWindowStart(),x.deliveryWindowEnd(),x.eta(),a==null?null:new AssignmentResponse(a.responsibleMembershipId(),a.responsibleDisplayName(),a.vehicleReference(),a.routeName()),x.temperatureMin(),x.temperatureMax(),x.temperatureUnit(),x.temperatureStatus(),x.podId(),x.podStatus(),x.version(),x.updatedAt(),x.alerts());}
    private ResponseEntity<DispatchResponse> mutation(LogisticsOperationsService.DispatchView x){return ResponseEntity.ok().eTag(etag(x.version())).body(response(x));}
    private ResponseEntity<DispatchResponse> created(DispatchResponse x){return ResponseEntity.status(201).eTag(etag(x.version())).body(x);}
    private static String etag(long version){return "\""+version+"\"";}
    private DispatchEventResponse event(LogisticsOperationsService.DispatchEventView x){return new DispatchEventResponse(x.id(),x.type(),x.fromStatus(),x.toStatus(),x.occurredAt(),x.buyerVisible(),x.summary());}
    private HandoffNoteResponse handoffNote(LogisticsOperationsService.HandoffNoteView x){return new HandoffNoteResponse(x.id(),x.dispatchOrderId(),x.note(),x.authorMembershipId(),x.occurredAt(),x.dispatchVersion());}
    private static long version(String value){if(value==null||value.isBlank())throw new LogisticsException("PRECONDITION_REQUIRED",false);try{long parsed=Long.parseLong(value.replace("\"",""));if(parsed<0)throw new NumberFormatException();return parsed;}catch(NumberFormatException e){throw new LogisticsException("PRECONDITION_REQUIRED",false);}}

    public record PageResponse<T>(List<T> items,int page,int size,long total){}
    public record DispatchResponse(String id,String dispatchNumber,String reservationId,String salesOrderId,String salesOrderNumber,String clientAccountId,String clientCode,String clientName,String status,String destination,String deliveryArea,String priority,Instant deliveryWindowStart,Instant deliveryWindowEnd,Instant eta,AssignmentResponse assignment,BigDecimal temperatureMin,BigDecimal temperatureMax,String temperatureUnit,String temperatureStatus,String podId,String podStatus,long version,Instant updatedAt,List<String> alerts){}
    public record AssignmentResponse(String responsibleMembershipId,String responsibleDisplayName,String vehicleReference,String routeName){}
    public record DispatchEventResponse(String id,String type,String fromStatus,String toStatus,String occurredAt,boolean buyerVisible,String summary){}
    public record HandoffNoteResponse(String id,String dispatchOrderId,String note,String authorMembershipId,Instant occurredAt,long dispatchVersion){}
    public record PodResponse(String podId,String dispatchOrderId,String dispatchNumber,String status,String receiverName,Instant completedAt,String notes,boolean photoEvidenceDeclared,boolean signatureEvidenceDeclared,Instant updatedAt){}
    public record AssignmentRequest(String responsibleMembershipId,String vehicleReference,String routeName){}
    public record ScheduleRequest(Instant deliveryWindowStart,Instant deliveryWindowEnd,Instant eta){}
    public record ReprogramRequest(Instant deliveryWindowStart,Instant deliveryWindowEnd,Instant eta,String reason){}
    public record TemperatureRequest(BigDecimal value,String unit,Instant recordedAt,String source){}
    public record IncidentRequest(String type,String severity,boolean buyerVisible,String description,Instant occurredAt,String resolution){}
    public record PodRequest(String receiverName,Instant completedAt,String notes,boolean photoEvidenceDeclared,boolean signatureEvidenceDeclared){}
    public record HandoffNoteRequest(String note){}
    public record ReasonRequest(String reason){}
}
