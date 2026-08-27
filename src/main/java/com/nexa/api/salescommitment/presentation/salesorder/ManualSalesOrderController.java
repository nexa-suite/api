package com.nexa.api.salescommitment.presentation.salesorder;

import com.nexa.api.salescommitment.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.presentation.SalesHttpHeaders;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales-orders/manual")
@Profile("!test")
@Tag(name = "Manual Sales Orders")
@SecurityRequirement(name = "bearerAuth")
public final class ManualSalesOrderController {
    private static final String ACCESS_CONTEXT = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final ManualSalesOrderUseCase orders;

    public ManualSalesOrderController(ManualSalesOrderUseCase orders) { this.orders = orders; }

    @PostMapping
    @Operation(operationId = "createManualSalesOrder", summary = "Create a manual sales order")
    public ResponseEntity<ManualSalesOrderView> create(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                       @Parameter(name = "Idempotency-Key", required = true)
                                                       @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody CreateRequest request) {
        var value = orders.create(context, request.command(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/sales-orders/" + value.id()))
                .eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    public record CreateRequest(@NotBlank @Size(max = 64) String clientAccountId,
                                @Size(max = 64) String addressId, @Valid ManualAddressRequest manualAddress,
                                @NotNull @FutureOrPresent LocalDate requestedDeliveryDate, @Size(max = 2000) String deliveryNotes,
                                @Size(max = 64) String warehouseId, @Size(max = 40) String routeProvider,
                                @NotBlank @Size(max = 40) String paymentOption, String priority,
                                @Size(max = 3) String currency, @Size(max = 2000) String notes,
                                @NotNull @Valid @Size(min = 1, max = 100) List<LineRequest> lines) {
        CreateManualSalesOrderCommand command() {
            return new CreateManualSalesOrderCommand(clientAccountId, addressId, manualAddress == null ? null : manualAddress.toDomain(),
                    requestedDeliveryDate, deliveryNotes, warehouseId, routeProvider, PaymentOption.from(paymentOption),
                    priority == null ? null : PurchaseRequestPriority.from(priority), currency, notes,
                    lines.stream().map(line -> new CreateManualSalesOrderCommand.Line(line.skuId(), line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList());
        }
    }

    /** Sales-owned transport shape translated into the Customer address value contract. */
    public record ManualAddressRequest(@Size(max = 32) String addressType,
                                       @NotBlank @Size(max = 240) String line,
                                       @Size(max = 500) String reference,
                                       @Size(max = 8) String countryCode,
                                       @NotBlank @Size(max = 40) String departmentCode,
                                       @NotBlank @Size(max = 40) String provinceCode,
                                       @NotBlank @Size(max = 40) String districtCode,
                                       @Size(max = 160) String recipientName,
                                       @Size(max = 48) String recipientPhone,
                                       @Size(max = 32) String roadType,
                                       @Size(max = 180) String streetName,
                                       @Size(max = 32) String streetNumber,
                                       @Size(max = 64) String interior,
                                       @Size(max = 32) String postalCode,
                                       @Size(max = 1000) String receivingInstructions,
                                       @Size(max = 240) String receivingHours,
                                       @Digits(integer = 3, fraction = 7) java.math.BigDecimal latitude,
                                       @Digits(integer = 3, fraction = 7) java.math.BigDecimal longitude,
                                       @Size(max = 240) String placeId,
                                       @Size(max = 24) String source) {
        Address toDomain() {
            return new Address(addressType, line, reference, countryCode == null ? "PE" : countryCode,
                    departmentCode, provinceCode, districtCode, recipientName, recipientPhone, roadType,
                    streetName, streetNumber, interior, postalCode, receivingInstructions, receivingHours,
                    latitude, longitude, placeId, source);
        }
    }

    public record LineRequest(UUID skuId, @Size(max = 64) String catalogItemId,
                              @jakarta.validation.constraints.NotNull java.math.BigDecimal quantity,
                              @Size(max = 32) String unit, @Size(max = 2000) String notes) { }
}
