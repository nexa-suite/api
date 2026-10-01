package com.nexa.api.customerbuyerrelationships.presentation.fieldvisit;

import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.service.FieldVisitService;
import com.nexa.api.customerbuyerrelationships.presentation.CustomerRelationshipHttpHeaders;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/client-accounts/{customerId}/field-visits")
public class FieldVisitController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final FieldVisitService visits;
    public FieldVisitController(FieldVisitService visits) { this.visits = visits; }
    @GetMapping @Operation(operationId = "listCustomerFieldVisitEvidence")
    public List<FieldVisitEvidence> list(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable String customerId) {
        return visits.list(context, customerId);
    }
    @PostMapping @Operation(operationId = "recordCustomerFieldVisitEvidence")
    public ResponseEntity<FieldVisitEvidence> record(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable String customerId, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(name="If-Match",required=false) String version, @RequestBody FieldVisitService.Command command) {
        return ResponseEntity.status(201).body(visits.record(context, customerId, CustomerRelationshipHttpHeaders.requireVersion(version), key, command));
    }
}
