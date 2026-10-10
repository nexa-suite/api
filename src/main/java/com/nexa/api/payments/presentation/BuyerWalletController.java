package com.nexa.api.payments.presentation;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.service.BuyerWalletReadService;
import com.nexa.api.payments.application.service.BuyerWalletRechargeService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/** Self-only Buyer read. Tenant and Buyer selectors are deliberately absent. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/buyer/wallet")
@Tag(name = "Buyer wallet")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerWalletController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final BuyerWalletReadService service;
    private final BuyerWalletRechargeService rechargeService;

    public BuyerWalletController(BuyerWalletReadService service, BuyerWalletRechargeService rechargeService) {
        this.service = service;
        this.rechargeService = rechargeService;
    }

    @GetMapping
    @Operation(operationId = "getCurrentBuyerWallet",
            description = "Reads the current Buyer’s PEN wallet and paginated movements from the provisioned Tenant database. "
                    + "Capabilities report server support only; they do not guarantee funds or approve an order.")
    public BuyerWalletModels.WalletView getCurrent(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return service.getCurrentBuyerWallet(context, page, size);
    }

    @PostMapping("/recharges")
    @Operation(operationId = "createBuyerWalletRecharge",
            description = "Creates a Tenant-local PEN recharge intent. Balance changes only after the verified provider callback.")
    public ResponseEntity<BuyerWalletModels.RechargeIntentView> createRecharge(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RechargeRequest request) {
        BuyerWalletModels.RechargeIntentView created = rechargeService.create(context, request.amount(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/buyer/wallet/recharges/" + created.id())).body(created);
    }

    @GetMapping("/recharges/{rechargeId}")
    @Operation(operationId = "getBuyerWalletRecharge",
            description = "Reads the current Buyer’s own Tenant-local recharge state. Provider client secrets are never returned.")
    public BuyerWalletModels.RechargeView getRecharge(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID rechargeId) {
        return rechargeService.get(context, rechargeId);
    }

    public record RechargeRequest(
            @DecimalMin(value = "0.01", inclusive = true) @DecimalMax(value = "999999.99", inclusive = true)
            BigDecimal amount) { }
}
