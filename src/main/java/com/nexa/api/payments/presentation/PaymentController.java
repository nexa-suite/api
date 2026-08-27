package com.nexa.api.payments.presentation;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.service.PaymentServiceFacade;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Payments and Receivables")
@SecurityRequirement(name = "bearerAuth")
public final class PaymentController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final PaymentServiceFacade service;

    public PaymentController(PaymentServiceFacade service) { this.service = service; }

    @GetMapping("/receivables")
    @Operation(operationId = "listReceivables")
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                              @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return service.listReceivables(context, page, size);
    }

    @PostMapping("/receivables")
    @Operation(operationId = "createReceivable")
    public ResponseEntity<PaymentModels.ReceivableView> createReceivable(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                          @Parameter(required = true, description = "Stable retry key") @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                          @Valid @RequestBody ReceivableRequest request) {
        PaymentModels.ReceivableView value = service.createReceivable(context, request.toCommand(idempotencyKey));
        return ResponseEntity.created(URI.create("/api/v1/receivables/" + value.id())).body(value);
    }

    @GetMapping("/receivables/{receivableId}")
    @Operation(operationId = "getReceivable")
    public PaymentModels.ReceivableView getReceivable(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID receivableId) { return service.getReceivable(context, receivableId); }

    @GetMapping("/receivables/{receivableId}/payments")
    @Operation(operationId = "listReceivablePayments")
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listReceivablePayments(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                                         @PathVariable UUID receivableId,
                                                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                                         @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return service.listPaymentsForReceivable(context, receivableId, page, size);
    }

    @PostMapping("/receivables/{receivableId}/payment-intents")
    @Operation(operationId = "createReceivablePaymentIntent")
    public ResponseEntity<PaymentModels.PaymentIntentView> createPaymentIntent(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID receivableId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentModels.PaymentIntentView value = service.createCardPaymentIntent(context, receivableId, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + value.paymentId())).body(value);
    }

    @PostMapping("/receivables/{receivableId}/credit-line-payments")
    @Operation(operationId = "createCreditLinePayment")
    public ResponseEntity<PaymentModels.PaymentView> createCreditLinePayment(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID receivableId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentModels.PaymentView value = service.createCreditLinePayment(context, receivableId, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + value.id())).body(value);
    }

    @PostMapping("/receivables/{receivableId}/bank-transfer-payments")
    @Operation(operationId = "createBankTransferPayment")
    public ResponseEntity<PaymentModels.PaymentView> createBankTransfer(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID receivableId,
                                                                        @Parameter(required = true) @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                        @Valid @RequestBody BankTransferRequest request) {
        PaymentModels.PaymentView value = service.createBankTransfer(context, receivableId, idempotencyKey, request.reference(), request.proofEvidenceId());
        return ResponseEntity.created(URI.create("/api/v1/payments/" + value.id())).body(value);
    }

    @GetMapping("/payments")
    @Operation(operationId = "listPayments")
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                               @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
                                                                               @RequestParam(required = false) String method,
                                                                               @RequestParam(required = false) String status) {
        return service.listPayments(context, page, size, method, status);
    }

    @GetMapping("/payments/reconciliation-cases")
    @Operation(operationId = "listPaymentReconciliationCases")
    public PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                                              @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
                                                                                              @RequestParam(required = false) String state) {
        return service.listReconciliationCases(context, page, size, state);
    }

    @PostMapping("/payments/reconciliation-cases/{caseId}/refund-retries")
    @Operation(operationId = "retryPaymentReconciliationRefund")
    public ResponseEntity<PaymentModels.ReconciliationCaseView> retryReconciliationCase(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                                         @PathVariable UUID caseId,
                                                                         @Parameter(required = true) @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                         @RequestBody(required = false) ReconciliationRetryRequest request) {
        PaymentModels.ReconciliationCaseView value = service.retryReconciliationCase(context, caseId,
                request == null ? null : request.operatorNote(), idempotencyKey);
        HttpStatus status = "REFUND_PENDING".equals(value.state()) || "REFUND_PROCESSING".equals(value.state())
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(value);
    }

    @PostMapping("/payments/{paymentId}/bank-transfer/approve")
    @Operation(operationId = "approveBankTransfer")
    public PaymentModels.PaymentView approveBankTransfer(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID paymentId,
                                                        @Parameter(required = true) @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.reviewBankTransfer(context, paymentId, "APPROVE", null, idempotencyKey);
    }

    @PostMapping("/payments/{paymentId}/bank-transfer/reject")
    @Operation(operationId = "rejectBankTransfer")
    public PaymentModels.PaymentView rejectBankTransfer(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID paymentId,
                                                        @Parameter(required = true) @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody BankTransferReviewRequest request) {
        return service.reviewBankTransfer(context, paymentId, "REJECT", request.reason(), idempotencyKey);
    }

    @PostMapping("/payments/{paymentId}/bank-transfer/reconcile")
    @Operation(operationId = "reconcileBankTransfer")
    public PaymentModels.PaymentView reconcileBankTransfer(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID paymentId,
                                                           @Parameter(required = true) @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.reviewBankTransfer(context, paymentId, "RECONCILE", null, idempotencyKey);
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(operationId = "getPayment")
    public PaymentModels.PaymentView getPayment(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID paymentId) { return service.getPayment(context, paymentId); }

    @PostMapping("/integrations/stripe/webhooks")
    @Operation(operationId = "receiveStripeWebhook")
    public ResponseEntity<PaymentModels.WebhookReceipt> webhook(@RequestBody String payload, @Parameter(required = true) @RequestHeader("Stripe-Signature") String signature) { return ResponseEntity.accepted().body(service.receiveStripeWebhook(payload, signature)); }

    public record ReceivableRequest(@NotBlank String subjectType, @NotNull UUID subjectId, java.time.Instant dueAt) {
        PaymentServiceFacade.ReceivableRequest toCommand(String idempotencyKey) { return new PaymentServiceFacade.ReceivableRequest(subjectType, subjectId, dueAt, idempotencyKey); }
    }

    public record BankTransferRequest(@NotBlank String reference, UUID proofEvidenceId) { }
    public record BankTransferReviewRequest(@NotBlank String reason) { }
    public record ReconciliationRetryRequest(String operatorNote) { }
}
