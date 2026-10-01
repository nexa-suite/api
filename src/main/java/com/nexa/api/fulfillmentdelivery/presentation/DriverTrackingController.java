package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.*;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@RestController @Profile("!test") @RequestMapping("/api/v1")
@Tag(name="Fulfillment & Delivery") @SecurityRequirement(name="bearerAuth")
public class DriverTrackingController {
 private static final String ACCESS="com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
 private final DriverTrackingService service;
 public DriverTrackingController(DriverTrackingService service){this.service=service;}
 @GetMapping("/driver/workdays/current") @Operation(operationId="getCurrentDriverWorkday")
 public ResponseEntity<Workday> current(@RequestAttribute(ACCESS) CurrentAccessContext c){return response(service.current(c));}
 @PostMapping("/driver/workdays") @Operation(operationId="startDriverWorkday")
 public ResponseEntity<Workday> start(@RequestAttribute(ACCESS) CurrentAccessContext c,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Availability request){
  if(!request.locationAvailable())throw DriverTrackingService.error("DRIVER_LOCATION_UNAVAILABLE",false);
  return response(service.command(c,null,null,"START",key));
 }
 @PostMapping("/driver/workdays/{id}/ends") @Operation(operationId="endDriverWorkday")
 public ResponseEntity<Workday> end(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestHeader("If-Match")String version){return response(service.command(c,id,version(version),"END",key));}
 @PostMapping("/driver/workdays/{id}/location-availability") @Operation(operationId="recordDriverLocationAvailability")
 public ResponseEntity<Workday> availability(@RequestAttribute(ACCESS) CurrentAccessContext c,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestHeader("If-Match")String version,@Valid @RequestBody Availability request){return response(service.command(c,id,version(version),request.locationAvailable()?"AVAILABLE":"UNAVAILABLE",key));}
 @PostMapping("/driver/workdays/{id}/locations") @Operation(operationId="captureDriverWorkdayLocation")
 public Coordinate capture(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID id,@Valid @RequestBody Sample request){return service.capture(c,id,request.sampleId(),request.latitude(),request.longitude(),request.accuracyMeters(),request.capturedAt());}
 @GetMapping("/driver/location") @Operation(operationId="getOwnDriverLocation")
 public Coordinate own(@RequestAttribute(ACCESS)CurrentAccessContext c){return service.ownLocation(c);}
 @GetMapping("/dispatch/drivers/{membershipId}/location") @Operation(operationId="getAuthorizedDispatchDriverLocation")
 public Coordinate dispatch(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID membershipId){return service.dispatchLocation(c,membershipId);}
 @GetMapping("/buyer/deliveries/{deliveryId}/live-location") @Operation(operationId="getBuyerOwnDeliveryLiveLocation")
 public DeliveryTracking buyer(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID deliveryId){return service.buyerLocation(c,deliveryId);}
 public record Availability(@NotNull Boolean locationAvailable) { }
 public record Sample(@NotNull UUID sampleId,@NotNull Double latitude,@NotNull Double longitude,@NotNull Double accuracyMeters,@NotNull Instant capturedAt) { }
 private static ResponseEntity<Workday> response(Workday day){return day==null?ResponseEntity.noContent().build():ResponseEntity.ok().eTag("\""+day.version()+"\"").body(day);}
 private static long version(String text){
  if(text==null || !text.matches("\"[0-9]+\""))throw DriverTrackingService.error("VERSION_INVALID",false);
  try{return Long.parseLong(text.substring(1,text.length()-1));}catch(NumberFormatException e){throw DriverTrackingService.error("VERSION_INVALID",false);}
 }
}
