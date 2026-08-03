package com.nexa.api.sales.presentation.clientaccountaddress;

import com.nexa.api.sales.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.sales.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.sales.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.sales.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.sales.presentation.SalesHttpHeaders;
import com.nexa.api.sales.presentation.request.DeliveryAddressRequest;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/client-accounts/{clientAccountId}/addresses")
@Profile("!test")
@Tag(name = "Client Account Addresses")
@SecurityRequirement(name = "bearerAuth")
public final class ClientAccountAddressController {
    private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final ClientAccountAddressUseCase addresses;

    public ClientAccountAddressController(ClientAccountAddressUseCase addresses) { this.addresses = addresses; }

    @GetMapping
    public List<AddressResponse> list(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                      @PathVariable String clientAccountId) {
        return addresses.list(context, clientAccountId).stream().map(AddressResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                  @PathVariable String clientAccountId,
                                                  @Valid @RequestBody CreateAddressRequest request) {
        var value = addresses.create(context, clientAccountId,
                new CreateClientAccountAddressCommand(request.label(), request.address().toDomain(), request.defaultAddress()));
        return ResponseEntity.status(201).eTag(SalesHttpHeaders.etag(value.version())).body(AddressResponse.from(value));
    }

    @PatchMapping("/{addressId}")
    public ResponseEntity<AddressResponse> update(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                  @PathVariable String clientAccountId, @PathVariable String addressId,
                                                  @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                  @Valid @RequestBody UpdateAddressRequest request) {
        var value = addresses.update(context, clientAccountId, addressId,
                new UpdateClientAccountAddressCommand(request.label(), request.address().toDomain()),
                SalesHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(AddressResponse.from(value));
    }

    @PutMapping("/{addressId}/default")
    public ResponseEntity<AddressResponse> setDefault(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                      @PathVariable String clientAccountId, @PathVariable String addressId,
                                                      @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = addresses.setDefault(context, clientAccountId, addressId, SalesHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(AddressResponse.from(value));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<AddressResponse> deactivate(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                      @PathVariable String clientAccountId, @PathVariable String addressId,
                                                      @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = addresses.deactivate(context, clientAccountId, addressId, SalesHttpHeaders.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(AddressResponse.from(value));
    }

    public record CreateAddressRequest(@NotBlank @Size(max = 120) String label,
                                       @Valid @NotNull DeliveryAddressRequest address,
                                       boolean defaultAddress) { }

    public record UpdateAddressRequest(@NotBlank @Size(max = 120) String label,
                                       @Valid @NotNull DeliveryAddressRequest address) { }

    public record AddressResponse(UUID id, String clientAccountId, String label, String addressType, String line,
                                  String reference, String countryCode, String departmentCode, String provinceCode,
                                  String districtCode, String recipientName, String recipientPhone, String roadType,
                                  String streetName, String streetNumber, String interior, String postalCode,
                                  String receivingInstructions, String receivingHours, java.math.BigDecimal latitude,
                                  java.math.BigDecimal longitude, String placeId, String source,
                                  boolean defaultAddress, boolean active, long version) {
        static AddressResponse from(ClientAccountAddressView value) {
            var address = value.address();
            return new AddressResponse(value.id(), value.clientAccountId(), value.label(), address.addressType(),
                    address.line(), address.reference(), address.countryCode(), address.departmentCode(),
                    address.provinceCode(), address.districtCode(), address.recipientName(), address.recipientPhone(),
                    address.roadType(), address.streetName(), address.streetNumber(), address.interior(), address.postalCode(),
                    address.receivingInstructions(), address.receivingHours(), address.latitude(), address.longitude(),
                    address.placeId(), address.source(), value.defaultAddress(), value.active(), value.version());
        }
    }
}
