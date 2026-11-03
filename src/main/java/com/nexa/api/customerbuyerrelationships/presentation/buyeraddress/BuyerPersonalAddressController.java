package com.nexa.api.customerbuyerrelationships.presentation.buyeraddress;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.CreateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.UpdateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressUseCase;
import com.nexa.api.customerbuyerrelationships.presentation.CustomerRelationshipHttpHeaders;
import com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.request.CreateBuyerAddressRequest;
import com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.request.UpdateBuyerAddressRequest;
import com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.response.BuyerAddressResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Buyer personal address book; company-wide addresses remain under Client Account Addresses. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1")
@Tag(name = "Buyer personal addresses")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerPersonalAddressController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final BuyerAddressUseCase addresses;

    public BuyerPersonalAddressController(BuyerAddressUseCase addresses) {
        this.addresses = addresses;
    }

    @GetMapping("/buyer/addresses")
    @Operation(operationId = "listCurrentBuyerPersonalAddresses",
            description = "Lists active personal delivery addresses owned by the authenticated Buyer membership.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Buyer personal addresses returned"),
            @ApiResponse(responseCode = "403", description = "Active Buyer membership and Portal access required"),
            @ApiResponse(responseCode = "503", description = "Tenant business database unavailable")
    })
    public List<BuyerAddressResponse> listMine(@RequestAttribute(ACCESS) CurrentAccessContext context) {
        return addresses.listMine(context).stream().map(BuyerAddressResponse::from).toList();
    }

    @PostMapping("/buyer/addresses")
    @Operation(operationId = "createCurrentBuyerPersonalAddress",
            description = "Creates a detailed personal address for the authenticated Buyer membership.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Personal address created; ETag contains version"),
            @ApiResponse(responseCode = "400", description = "Address payload is invalid"),
            @ApiResponse(responseCode = "403", description = "Buyer write permission required"),
            @ApiResponse(responseCode = "503", description = "Tenant business database unavailable")
    })
    public ResponseEntity<BuyerAddressResponse> createMine(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Valid @RequestBody CreateBuyerAddressRequest request) {
        var value = addresses.createMine(context, new CreateBuyerAddressCommand(request.label(),
                request.address().toDomain(), request.defaultAddress()));
        return ResponseEntity.status(201).eTag(CustomerRelationshipHttpHeaders.etag(value.version()))
                .body(BuyerAddressResponse.from(value));
    }

    @PatchMapping("/buyer/addresses/{addressId}")
    @Operation(operationId = "updateCurrentBuyerPersonalAddress",
            description = "Updates one personal address using mandatory If-Match optimistic concurrency.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Personal address updated; new ETag returned"),
            @ApiResponse(responseCode = "404", description = "Address does not belong to this Buyer"),
            @ApiResponse(responseCode = "409", description = "Address changed since supplied ETag"),
            @ApiResponse(responseCode = "428", description = "If-Match is required")
    })
    public ResponseEntity<BuyerAddressResponse> updateMine(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateBuyerAddressRequest request) {
        var value = addresses.updateMine(context, addressId,
                new UpdateBuyerAddressCommand(request.label(), request.address().toDomain()),
                CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version()))
                .body(BuyerAddressResponse.from(value));
    }

    @PutMapping("/buyer/addresses/{addressId}/default")
    @Operation(operationId = "setCurrentBuyerPersonalDefaultAddress",
            description = "Marks one active personal address as this Buyer membership's default using If-Match.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default address updated; new ETag returned"),
            @ApiResponse(responseCode = "404", description = "Address does not belong to this Buyer"),
            @ApiResponse(responseCode = "409", description = "Address changed since supplied ETag"),
            @ApiResponse(responseCode = "428", description = "If-Match is required")
    })
    public ResponseEntity<BuyerAddressResponse> setDefaultMine(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = addresses.setDefaultMine(context, addressId, CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version()))
                .body(BuyerAddressResponse.from(value));
    }

    @DeleteMapping("/buyer/addresses/{addressId}")
    @Operation(operationId = "deactivateCurrentBuyerPersonalAddress",
            description = "Deactivates one personal address while retaining its database history. If-Match is mandatory.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Address deactivated; history retained and new ETag returned"),
            @ApiResponse(responseCode = "409", description = "Address changed since supplied ETag"),
            @ApiResponse(responseCode = "428", description = "If-Match is required")
    })
    public ResponseEntity<BuyerAddressResponse> deactivateMine(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = addresses.deactivateMine(context, addressId,
                CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version()))
                .body(BuyerAddressResponse.from(value));
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
