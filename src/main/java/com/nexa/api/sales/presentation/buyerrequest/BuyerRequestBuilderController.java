package com.nexa.api.sales.presentation.buyerrequest;

import com.nexa.api.sales.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand;
import com.nexa.api.sales.application.buyerrequest.port.BuyerRequestBuilderUseCase;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.presentation.request.DeliveryAddressRequest;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/buyer-requests")
@Profile("!test")
@Tag(name = "Buyer Request Builder")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerRequestBuilderController {
    private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final BuyerRequestBuilderUseCase requests;

    public BuyerRequestBuilderController(BuyerRequestBuilderUseCase requests) { this.requests = requests; }

    @PostMapping("/previews")
    public BuyerRequestSnapshot preview(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                        @Valid @RequestBody CreateRequest request) {
        return requests.preview(context, request.command());
    }

    @PostMapping
    public ResponseEntity<BuyerRequestView> create(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
                                                   @Valid @RequestBody CreateRequest request) {
        var value = requests.create(context, request.command());
        return ResponseEntity.status(201).body(value);
    }

    public record CreateRequest(@Size(max = 64) String clientAccountId, @Size(max = 64) String addressId,
                                @Valid DeliveryAddressRequest manualAddress, @NotNull @FutureOrPresent LocalDate requestedDeliveryDate,
                                @Size(max = 2000) String deliveryNotes, @Size(max = 64) String warehouseId,
                                @Size(max = 40) String routeProvider, @NotBlank @Size(max = 40) String paymentOption,
                                @Size(max = 2000) String comments,
                                @NotNull @Valid @Size(min = 1, max = 100) List<LineRequest> lines) {
        CreateBuyerRequestCommand command() {
            return new CreateBuyerRequestCommand(clientAccountId, addressId, manualAddress == null ? null : manualAddress.toDomain(),
                    requestedDeliveryDate, deliveryNotes, warehouseId, routeProvider, PaymentOption.from(paymentOption), comments,
                    lines.stream().map(line -> new CreateBuyerRequestCommand.Line(line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList());
        }
    }

    public record LineRequest(@NotBlank @Size(max = 64) String catalogItemId,
                              @jakarta.validation.constraints.NotNull java.math.BigDecimal quantity,
                              @Size(max = 32) String unit, @Size(max = 2000) String notes) { }
}
