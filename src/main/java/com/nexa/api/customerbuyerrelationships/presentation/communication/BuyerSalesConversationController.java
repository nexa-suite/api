package com.nexa.api.customerbuyerrelationships.presentation.communication;

import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerSalesConversationUseCase;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessagePage;
import com.nexa.api.customerbuyerrelationships.presentation.communication.request.SendClientAccountBuyerSalesMessageRequest;
import com.nexa.api.customerbuyerrelationships.presentation.communication.request.SendBuyerSalesMessageRequest;
import com.nexa.api.customerbuyerrelationships.presentation.communication.response.BuyerSalesMessagePageResponse;
import com.nexa.api.customerbuyerrelationships.presentation.communication.response.BuyerSalesMessageResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP polling contract for one Buyer and its assigned Sales membership. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Buyer and Sales messages")
public final class BuyerSalesConversationController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final BuyerSalesConversationUseCase conversations;

    public BuyerSalesConversationController(BuyerSalesConversationUseCase conversations) {
        this.conversations = conversations;
    }

    @GetMapping("/api/v1/buyer/messages")
    @Operation(operationId = "listCurrentBuyerSalesMessages", description =
            "Returns the authenticated Buyer membership's active Client Account message stream. "
                    + "Pass afterMessageId from the last item to poll for later messages. Results are ordered "
                    + "by creation time and message id. This is HTTP polling; no realtime delivery is implied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ordered message page"),
            @ApiResponse(responseCode = "400", description = "Invalid page size or message cursor"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "An active Buyer relationship is required"),
            @ApiResponse(responseCode = "404", description = "Cursor does not belong to the current account stream"),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable")
    })
    public BuyerSalesMessagePageResponse listBuyer(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(required = false) UUID afterMessageId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return response(conversations.listForBuyer(context, afterMessageId, size));
    }

    @PostMapping("/api/v1/buyer/messages")
    @Operation(operationId = "sendCurrentBuyerSalesMessage", description =
            "Appends plain text to the active Buyer account stream. The account is resolved from the authenticated "
                    + "membership; callers cannot select another account. Retries with the same Idempotency-Key "
                    + "and body return the original message. No attachments or realtime transport are included.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Message created"),
            @ApiResponse(responseCode = "200", description = "Original message returned for an idempotent retry"),
            @ApiResponse(responseCode = "400", description = "Invalid message or missing idempotency key"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "An active Buyer relationship is required"),
            @ApiResponse(responseCode = "409", description = "Idempotency key was reused for another request"),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable")
    })
    public ResponseEntity<BuyerSalesMessageResponse> sendBuyer(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "Stable retry key for this message")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendBuyerSalesMessageRequest request) {
        BuyerSalesConversationUseCase.SendResult result = conversations.sendForBuyer(context, idempotencyKey,
                request.body());
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(response(result.message()));
    }

    @GetMapping("/api/v1/client-accounts/{clientAccountId}/messages")
    @Operation(operationId = "listClientAccountBuyerSalesMessages", description =
            "Returns one account's ordered message stream to its assigned Sales membership, Company Owner, "
                    + "or read-only Business Operations Manager under existing account-read scope. Other roles "
                    + "and unassigned Sales memberships cannot use this route. HTTP polling only; no realtime claim.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ordered message page"),
            @ApiResponse(responseCode = "400", description = "Invalid account id, page size, or message cursor"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Role or permission does not allow message access"),
            @ApiResponse(responseCode = "404", description = "Account or cursor not found in this scope"),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable")
    })
    public BuyerSalesMessagePageResponse listAccount(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable String clientAccountId,
            @RequestParam(required = false) UUID afterMessageId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return response(conversations.listForAccount(context, clientAccountId, afterMessageId, size));
    }

    @PostMapping("/api/v1/client-accounts/{clientAccountId}/messages")
    @Operation(operationId = "sendClientAccountBuyerSalesMessage", description =
            "Appends plain text as the assigned Sales membership to the account stream. Requires the existing "
                    + "client-management permission and account assignment. recipientBuyerMembershipId must be "
                    + "associated with the account. Retries with the same Idempotency-Key, recipient, and body "
                    + "return the original message. BOM and other audit-only readers cannot send.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Message created"),
            @ApiResponse(responseCode = "200", description = "Original message returned for an idempotent retry"),
            @ApiResponse(responseCode = "400", description = "Invalid account id, message, or idempotency key"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Role or permission does not allow sending"),
            @ApiResponse(responseCode = "404", description = "Account assignment or Buyer recipient is unavailable"),
            @ApiResponse(responseCode = "409", description = "Idempotency key was reused for another request"),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable")
    })
    public ResponseEntity<BuyerSalesMessageResponse> sendAccount(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable String clientAccountId,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "Stable retry key for this message")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendClientAccountBuyerSalesMessageRequest request) {
        BuyerSalesConversationUseCase.SendResult result = conversations.sendForAccount(context, clientAccountId,
                request.recipientBuyerMembershipId(), idempotencyKey, request.body());
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(response(result.message()));
    }

    private static BuyerSalesMessagePageResponse response(BuyerSalesMessagePage page) {
        return new BuyerSalesMessagePageResponse(page.items().stream()
                .map(BuyerSalesConversationController::response).toList(), page.nextAfterMessageId(), page.hasMore());
    }

    private static BuyerSalesMessageResponse response(BuyerSalesMessage message) {
        return new BuyerSalesMessageResponse(message.id(), message.clientAccountId(), message.buyerMembershipId(),
                message.senderMembershipId(), message.senderKind(), message.body(), message.createdAt());
    }
}
