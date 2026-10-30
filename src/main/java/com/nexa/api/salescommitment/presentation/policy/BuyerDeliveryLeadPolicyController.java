package com.nexa.api.salescommitment.presentation.policy;

import com.nexa.api.salescommitment.application.policy.BuyerDeliveryLeadPolicyQuery;
import com.nexa.api.salescommitment.application.policy.BuyerDeliveryLeadPolicyView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/buyer/delivery-lead-policy")
@Tag(name = "Buyer delivery policy")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerDeliveryLeadPolicyController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final BuyerDeliveryLeadPolicyQuery policy;

    public BuyerDeliveryLeadPolicyController(BuyerDeliveryLeadPolicyQuery policy) {
        this.policy = policy;
    }

    @GetMapping
    @Operation(operationId = "getBuyerDeliveryLeadPolicy",
            summary = "Read the fixed global Buyer delivery lead policy",
            description = "Counts Monday through Friday from the day after the current America/Lima date. "
                    + "No holiday calendar or Workspace configuration applies.")
    public ResponseEntity<BuyerDeliveryLeadPolicyResponse> get(
            @RequestAttribute(ACCESS) CurrentAccessContext context) {
        BuyerDeliveryLeadPolicyView value = policy.get(context);
        BuyerDeliveryLeadPolicyResponse response = new BuyerDeliveryLeadPolicyResponse(
                value.minimumDeliveryLeadBusinessDays(), value.minimumAllowedDeliveryDate(), value.timezone());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
