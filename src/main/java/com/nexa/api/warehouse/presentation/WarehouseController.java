package com.nexa.api.warehouse.presentation;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name="Warehouse Operations")
@SecurityRequirement(name="bearerAuth")
public final class WarehouseController {
    private static final String ACCESS = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final WarehouseOperationsService service;
    public WarehouseController(WarehouseOperationsService service){this.service=service;}

    @GetMapping("/warehouses") public WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> warehouses(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="code,asc") String sort){return service.warehouses(c,page,size,sort);}
    @GetMapping("/warehouses/{id}") public WarehouseOperationsService.WarehouseSummary warehouse(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return service.warehouse(c,id);}
    @PostMapping("/warehouses") public ResponseEntity<WarehouseOperationsService.WarehouseSummary> createWarehouse(@RequestAttribute(ACCESS) CurrentAccessContext c,@Valid @RequestBody WarehouseRequest r){return ResponseEntity.status(201).body(service.createWarehouse(c,r.code(),r.name(),r.address()));}
    @PatchMapping("/warehouses/{id}") public ResponseEntity<WarehouseOperationsService.WarehouseSummary> updateWarehouse(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestBody WarehousePatch r){long v=version(ifMatch);var result=service.updateWarehouse(c,id,r.name(),r.address(),r.status(),v);return ResponseEntity.ok().eTag(etag(result.version())).body(result);}

    @GetMapping("/warehouses/{warehouseId}/zones") public WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String warehouseId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.zones(c,warehouseId,page,size);}
    @PostMapping("/warehouses/{warehouseId}/zones") public ResponseEntity<WarehouseOperationsService.ZoneSummary> createZone(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String warehouseId,@RequestBody ZoneRequest r){return ResponseEntity.status(201).body(service.createZone(c,warehouseId,r.code(),r.name(),r.type(),r.temperatureMin(),r.temperatureMax()));}

    @GetMapping("/inventory") public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> inventory(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String catalogItemId,@RequestParam(required=false) String warehouseId,@RequestParam(required=false) String zoneId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="expirationDate,asc") String sort){return service.lots(c,catalogItemId,warehouseId,zoneId,status,page,size,sort);}
    @GetMapping("/inventory/lots") public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> lots(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String catalogItemId,@RequestParam(required=false) String warehouseId,@RequestParam(required=false) String zoneId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="expirationDate,asc") String sort){return inventory(c,catalogItemId,warehouseId,zoneId,status,page,size,sort);}
    @GetMapping("/inventory/lots/{lotId}") public WarehouseOperationsService.LotSummary lot(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String lotId){return service.lot(c,lotId);}
    @GetMapping("/inventory/movements") public WarehouseOperationsService.Page<WarehouseOperationsService.MovementSummary> movements(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String lotId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="occurredAt,desc") String sort){return service.movements(c,lotId,page,size,sort);}

    @PostMapping("/inventory/inbound-receipts") public ResponseEntity<WarehouseOperationsService.LotSummary> receive(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestHeader("Idempotency-Key") String key,@RequestBody WarehouseOperationsService.Receipt r,@RequestAttribute(value="com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId",required=false) Object correlation){return ResponseEntity.status(201).body(service.receive(c,r,key,String.valueOf(correlation)));}
    @PostMapping("/inventory/adjustments") public ResponseEntity<WarehouseOperationsService.LotSummary> adjust(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader("Idempotency-Key") String key,@RequestBody QuantityRequest r,@RequestAttribute(value="com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId",required=false) Object correlation){var result=service.adjust(c,r.lotId(),r.quantity(),r.direction()==null||r.direction().equalsIgnoreCase("IN"),r.reason(),version(ifMatch),key,String.valueOf(correlation));return ResponseEntity.ok().eTag(etag(result.version())).body(result);}
    @PostMapping("/inventory/waste-movements") public ResponseEntity<WarehouseOperationsService.LotSummary> waste(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader("Idempotency-Key") String key,@RequestBody QuantityRequest r,@RequestAttribute(value="com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId",required=false) Object correlation){var result=service.waste(c,r.lotId(),r.quantity(),r.reason(),version(ifMatch),key,String.valueOf(correlation));return ResponseEntity.ok().eTag(etag(result.version())).body(result);}

    @GetMapping("/inventory-availability") public List<WarehouseOperationsService.Availability> availability(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String catalogItemId,@RequestParam(required=false) List<String> catalogItemIds){return service.availability(c,catalogItemIds!=null&&!catalogItemIds.isEmpty()?catalogItemIds:List.of(catalogItemId));}
    @GetMapping("/fulfillment-candidates/{salesOrderId}/inventory-reservation-preview") public WarehouseOperationsService.ReservationPreview preview(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String salesOrderId){return service.preview(c,salesOrderId);}
    @PostMapping("/fulfillment-candidates/{salesOrderId}/inventory-reservations") public ResponseEntity<WarehouseOperationsService.ReservationDetail> reserve(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String salesOrderId,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader("Idempotency-Key") String key,@RequestAttribute(value="com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId",required=false) Object correlation){var result=service.reserve(c,salesOrderId,version(ifMatch),key,String.valueOf(correlation));return ResponseEntity.status(result.status().equals("RESERVED")?201:409).body(result);}
    @GetMapping("/inventory-reservations") public WarehouseOperationsService.Page<WarehouseOperationsService.ReservationSummary> reservations(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.reservations(c,status,page,size);}
    @GetMapping("/inventory-reservations/{id}") public WarehouseOperationsService.ReservationDetail reservation(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return service.reservation(c,id);}
    @PostMapping("/inventory-reservations/{id}/releases") public WarehouseOperationsService.ReservationDetail release(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader("Idempotency-Key") String key,@RequestBody ReasonRequest r,@RequestAttribute(value="com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId",required=false) Object correlation){return service.release(c,id,version(ifMatch),key,r.reason(),String.valueOf(correlation),false);}
    @GetMapping("/dispatch-readiness-candidates") public List<WarehouseOperationsService.ReadinessCandidate> readiness(@RequestAttribute(ACCESS) CurrentAccessContext c){return service.readiness(c);}
    @GetMapping("/dispatch-readiness-candidates/{id}") public WarehouseOperationsService.ReadinessCandidate readinessOne(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable String id){return service.readiness(c).stream().filter(x->x.reservationId().equals(id)).findFirst().orElseThrow(()->new WarehouseOperationsService.WarehouseException("DISPATCH_READINESS_CANDIDATE_NOT_FOUND",true));}

    private static long version(String v){if(v==null||v.isBlank())throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED",false);try{return Long.parseLong(v.replace("\"",""));}catch(NumberFormatException e){throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED",false);}}
    private static String etag(long v){return "\""+v+"\"";}
    public record WarehouseRequest(String code,String name,String address){} public record WarehousePatch(String name,String address,String status){} public record ZoneRequest(String code,String name,String type,BigDecimal temperatureMin,BigDecimal temperatureMax){} public record QuantityRequest(String lotId,BigDecimal quantity,String direction,String reason){} public record ReasonRequest(String reason){}
}
