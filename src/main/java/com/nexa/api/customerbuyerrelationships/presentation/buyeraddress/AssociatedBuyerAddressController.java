package com.nexa.api.customerbuyerrelationships.presentation.buyeraddress;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressUseCase;
import com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.response.BuyerAddressResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Local read-only audit of the personal addresses of an associated Buyer. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1")
@Tag(name = "Buyer personal addresses")
@SecurityRequirement(name = "bearerAuth")
public final class AssociatedBuyerAddressController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final BuyerAddressUseCase addresses;

    public AssociatedBuyerAddressController(BuyerAddressUseCase addresses) {
        this.addresses = addresses;
    }

    @GetMapping("/client-accounts/{clientAccountId}/buyers/{buyerMembershipId}/addresses")
    @Operation(operationId = "listAssociatedBuyerPersonalAddresses",
            description = "Read-only audit view for the exact Buyer membership linked to this active Client Account. "
                    + "Only its assigned Sales Representative or Tenant-local BOM may call it. Personal addresses remain "
                    + "membership-owned and cannot be managed by workforce users.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Associated Buyer personal addresses returned"),
            @ApiResponse(responseCode = "403", description = "Assigned Sales Representative or BOM read access required"),
            @ApiResponse(responseCode = "404", description = "Account, assignment, Buyer relationship, or membership is unavailable"),
            @ApiResponse(responseCode = "503", description = "Tenant business database unavailable")
    })
    public List<BuyerAddressResponse> listForAccountBuyer(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID clientAccountId, @PathVariable UUID buyerMembershipId) {
        return addresses.listForAccountBuyer(context, clientAccountId.toString(), buyerMembershipId).stream()
                .map(BuyerAddressResponse::from).toList();
    }
}
