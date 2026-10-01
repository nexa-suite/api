package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.CustomerInstructionModels.Snapshot;
import com.nexa.api.fulfillmentdelivery.application.service.CustomerInstructionService;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name="Fulfillment & Delivery")
@SecurityRequirement(name="bearerAuth")
public class CustomerInstructionController {
    private static final String ACCESS="com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final CustomerInstructionService service;
    public CustomerInstructionController(CustomerInstructionService service) { this.service=service; }
    @GetMapping("/sales-orders/{orderId}/customer-delivery-instructions")
    @Operation(operationId="getSalesRecordedCustomerDeliveryInstructions")
    public ResponseEntity<Snapshot> sales(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID orderId) {
        return response(service.read(c,orderId,false));
    }
    @GetMapping("/buyer/sales-orders/{orderId}/customer-delivery-instructions")
    @Operation(operationId="getBuyerCustomerDeliveryInstructions")
    public ResponseEntity<Snapshot> buyer(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID orderId) {
        return response(service.read(c,orderId,true));
    }
    @PostMapping("/sales-orders/{orderId}/customer-delivery-instructions")
    @Operation(operationId="recordSalesReceivedCustomerDeliveryInstruction")
    public ResponseEntity<Snapshot> salesPublish(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID orderId,
            @RequestHeader("If-Match")String version,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Publication request) {
        return response(service.publish(c,orderId,false,version(version),request.instructionId(),request.kind(),request.content(),request.sourceReference(),key));
    }
    @PostMapping("/buyer/sales-orders/{orderId}/customer-delivery-instructions")
    @Operation(operationId="publishBuyerCustomerDeliveryInstruction")
    public ResponseEntity<Snapshot> buyerPublish(@RequestAttribute(ACCESS)CurrentAccessContext c,@PathVariable UUID orderId,
            @RequestHeader("If-Match")String version,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Publication request) {
        return response(service.publish(c,orderId,true,version(version),request.instructionId(),request.kind(),request.content(),null,key));
    }
    public record Publication(UUID instructionId,@NotNull DeliveryInstructionKind kind,
                              @NotBlank @Size(max=2000)String content,@Size(max=500)String sourceReference) { }
    private static ResponseEntity<Snapshot> response(Snapshot value) { return ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value); }
    private static long version(String text) {
        if (text==null || !text.matches("\"[0-9]+\"")) throw new FulfillmentOperationException("VERSION_INVALID",false);
        try { return Long.parseLong(text.substring(1,text.length()-1)); }
        catch(NumberFormatException e) { throw new FulfillmentOperationException("VERSION_INVALID",false); }
    }
}
