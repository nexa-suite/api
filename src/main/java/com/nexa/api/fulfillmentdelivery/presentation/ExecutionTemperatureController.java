package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.*;
import com.nexa.api.fulfillmentdelivery.application.service.ExecutionTemperatureService;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class ExecutionTemperatureController {
    private static final String ACCESS="com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final ExecutionTemperatureService service;
    public ExecutionTemperatureController(ExecutionTemperatureService service) { this.service=service; }
    @GetMapping("/driver/deliveries/{deliveryId}/execution-temperature-readings")
    public ResponseEntity<Snapshot> driverRead(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID deliveryId) {
        Snapshot result=service.read(c,deliveryId,true); return ResponseEntity.ok().eTag(tag(result.deliveryVersion())).body(result);
    }
    @GetMapping("/deliveries/{deliveryId}/execution-temperature-readings")
    public ResponseEntity<Snapshot> dispositionRead(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID deliveryId) {
        Snapshot result=service.read(c,deliveryId,false); return ResponseEntity.ok().eTag(tag(result.deliveryVersion())).body(result);
    }
    @PostMapping("/driver/deliveries/{deliveryId}/execution-temperature-readings")
    public ResponseEntity<Reading> record(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID deliveryId,
            @RequestHeader(value="If-Match",required=false)String version,@RequestHeader("Idempotency-Key")String key,@RequestBody ReadingCommand command) {
        Reading result=service.record(c,deliveryId,version(version),key,command);
        return ResponseEntity.status(result.replayed()?200:201).eTag(tag(result.deliveryVersion())).body(result);
    }
    @PostMapping("/deliveries/{deliveryId}/execution-holds/{holdId}/dispositions")
    public ResponseEntity<DispositionResult> dispose(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID deliveryId,
            @PathVariable UUID holdId,@RequestHeader(value="If-Match",required=false)String version,@RequestHeader("Idempotency-Key")String key,
            @RequestBody DispositionCommand command) {
        if(command==null) throw new FulfillmentOperationException("INVALID_REQUEST",false);
        DispositionResult result=service.dispose(c,deliveryId,holdId,version(version),key,command.disposition(),command.reason());
        return ResponseEntity.status(result.replayed()?200:201).eTag(tag(result.deliveryVersion())).body(result);
    }
    public record DispositionCommand(Disposition disposition,String reason) { }
    private static String tag(long value) { return "\""+value+"\""; }
    private static long version(String value) {
        if(value==null) throw new FulfillmentOperationException("PRECONDITION_REQUIRED",false);
        if(!value.matches("\"[0-9]+\"")) throw new FulfillmentOperationException("VERSION_INVALID",false);
        try { return Long.parseLong(value.substring(1,value.length()-1)); } catch(NumberFormatException e) { throw new FulfillmentOperationException("VERSION_INVALID",false); }
    }
}
