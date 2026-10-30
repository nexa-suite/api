package com.nexa.api.customerbuyerrelationships.presentation.clientaccount;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountRegistrationResult;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.customerbuyerrelationships.presentation.CustomerRelationshipHttpHeaders;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.mapper.ClientAccountHttpMapper;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.AssociateBuyerMembershipRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.AssignClientAccountSalesOwnerRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.CreateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.RegisterBuyerClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.UpdateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountDetailResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.BuyerClientAccountRegistrationResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.BuyerClientAccountSelfResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountPageResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountSalesOwnerAssignmentResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountBuyerMembershipPageResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountBuyerMembershipResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-accounts")
@Profile("!test")
@Validated
@Tag(name = "Client Accounts")
@SecurityRequirement(name = "bearerAuth")
public class ClientAccountController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final ClientAccountUseCase sales;
	private final ClientAccountHttpMapper mapper;
	public ClientAccountController(ClientAccountUseCase sales, ClientAccountHttpMapper mapper) { this.sales = sales; this.mapper = mapper; }

	@GetMapping
	@Operation(operationId = "listClientAccounts")
	public ClientAccountPageResponse list(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestParam(required = false) String search, @RequestParam(required = false) @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
			@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
		return mapper.page(sales.list(context, search, status, page, size));
	}

	@GetMapping("/{id}")
	@Operation(operationId = "getClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Client Account returned", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "404", description = "Client Account not found")})
	public ResponseEntity<ClientAccountDetailResponse> detail(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) {
		var value = sales.detail(context, id); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@GetMapping("/me")
	@Operation(operationId = "getMyClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Buyer client account returned", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "404", description = "Buyer client account not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail")))})
	public ResponseEntity<BuyerClientAccountSelfResponse> buyerDetail(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context) {
		var value = sales.buyerDetail(context); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.buyerSelf(value, context.membershipId().toString()));
	}

	@PostMapping("/me")
	@Operation(operationId = "registerMyClientAccount", summary = "Register the authenticated Buyer's own Customer Account")
	@ApiResponses({@ApiResponse(responseCode = "201", description = "Buyer Customer Account registered", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "200", description = "Exact registration retry returned the existing account", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Buyer already belongs to a different account or registration conflicts")})
	public ResponseEntity<BuyerClientAccountRegistrationResponse> registerOwnBusiness(
			@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@Valid @RequestBody RegisterBuyerClientAccountRequest request) {
		ClientAccountRegistrationResult result = sales.registerOwnBusiness(context, mapper.buyerRegistration(request));
		return ResponseEntity.status(result.created() ? 201 : 200)
				.eTag(CustomerRelationshipHttpHeaders.etag(result.account().version()))
				.body(mapper.buyerRegistration(result.account()));
	}

	@GetMapping("/buyer-membership-candidates")
	@Operation(operationId = "listBuyerMembershipCandidates")
	public List<BuyerMembershipCandidate> buyerMembershipCandidates(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context) {
		return sales.buyerMembershipCandidates(context);
	}

	@PostMapping
	@Operation(operationId = "createClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "201", description = "Client Account created", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "400", description = "Invalid request"), @ApiResponse(responseCode = "409", description = "Duplicate or concurrent request")})
	public ResponseEntity<ClientAccountDetailResponse> create(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @Valid @RequestBody CreateClientAccountRequest request) {
		var value = sales.create(context, mapper.create(request)); return ResponseEntity.status(201).eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PatchMapping("/{id}")
	@Operation(operationId = "updateClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Client Account updated", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "400", description = "Invalid request"), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<ClientAccountDetailResponse> update(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody UpdateClientAccountRequest request) {
		var value = sales.update(context, id, mapper.update(request), CustomerRelationshipHttpHeaders.requireVersion(ifMatch)); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PostMapping("/{id}/activations")
	@Operation(operationId = "activateClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Client Account activated", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<ClientAccountDetailResponse> activate(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var value = sales.changeStatus(context, id, "ACTIVE", CustomerRelationshipHttpHeaders.requireVersion(ifMatch)); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PostMapping("/{id}/suspensions")
	@Operation(operationId = "suspendClientAccount")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Client Account suspended", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<ClientAccountDetailResponse> suspend(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var value = sales.changeStatus(context, id, "SUSPENDED", CustomerRelationshipHttpHeaders.requireVersion(ifMatch)); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PutMapping("/{id}/buyer-membership")
	@Operation(operationId = "associateClientAccountBuyerMembership")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Buyer membership associated", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match or duplicate association")})
	public ResponseEntity<ClientAccountDetailResponse> associateBuyer(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody AssociateBuyerMembershipRequest request) {
		var value = sales.associateBuyer(context, id, request.membershipId(), CustomerRelationshipHttpHeaders.requireVersion(ifMatch)); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@GetMapping("/{id}/buyer-memberships")
	@Operation(operationId = "listClientAccountBuyerMemberships", summary = "List Buyer memberships linked to one Client Account",
			description = "Requires client.read in the active Tenant and Workspace. Sales sees only accounts assigned to that Sales membership; Company Owner may read managed accounts; BOM has audit read access. Active Buyer email and display name come from the current Tenant Access membership, not stored in the relationship row. Inactive links remain visible for account history.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Buyer membership links returned", headers = @Header(name = "ETag", description = "Current Client Account version")),
			@ApiResponse(responseCode = "403", description = "Caller cannot read this Client Account"),
			@ApiResponse(responseCode = "404", description = "Client Account not found in the active Tenant and Workspace")
	})
	public ResponseEntity<ClientAccountBuyerMembershipPageResponse> buyerMemberships(
			@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
		var result = sales.buyerMemberships(context, id, page, size);
		return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(result.accountVersion()))
				.body(mapper.buyerMembershipPage(result));
	}

	@PostMapping("/{id}/buyer-memberships")
	@Operation(operationId = "associateClientAccountBuyerMembershipCollection", summary = "Associate or reactivate a Buyer membership",
			description = "Requires sales.write and client.manage within the active Tenant and Workspace. Buyer must be active in the same scope. Business accounts can link multiple Buyer memberships; Individual accounts remain limited to one person. A membership cannot be moved to a different account. If-Match uses the current Client Account ETag.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "New Buyer membership association created", headers = @Header(name = "ETag", description = "Current Client Account version")),
			@ApiResponse(responseCode = "200", description = "Existing association returned or inactive association reactivated", headers = @Header(name = "ETag", description = "Current Client Account version")),
			@ApiResponse(responseCode = "403", description = "Caller cannot manage this Client Account"),
			@ApiResponse(responseCode = "404", description = "Client Account or active same-scope Buyer membership not found"),
			@ApiResponse(responseCode = "409", description = "Stale If-Match, membership already assigned elsewhere, or Individual account already has a Buyer")
	})
	public ResponseEntity<ClientAccountBuyerMembershipResponse> associateBuyerMembership(
			@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody AssociateBuyerMembershipRequest request) {
		ClientAccountBuyerMembershipMutation result = sales.associateBuyerMembership(context, id,
				request.membershipId(), CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.status(result.created() ? 201 : 200)
				.eTag(CustomerRelationshipHttpHeaders.etag(result.accountVersion()))
				.body(mapper.buyerMembership(result.membership()));
	}

	@DeleteMapping("/{id}/buyer-memberships/{membershipId}")
	@Operation(operationId = "deactivateClientAccountBuyerMembership", summary = "Deactivate a Buyer membership link without deleting history",
			description = "Requires sales.write and client.manage within the active Tenant and Workspace. The relationship remains stored, preserving audit references, chat history, and Buyer-owned records. Repeated deletion is idempotent. If-Match uses the current Client Account ETag.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Buyer membership link deactivated or already inactive", headers = @Header(name = "ETag", description = "Current Client Account version")),
			@ApiResponse(responseCode = "403", description = "Caller cannot manage this Client Account"),
			@ApiResponse(responseCode = "404", description = "Client Account or Buyer membership link not found"),
			@ApiResponse(responseCode = "409", description = "Stale If-Match")
	})
	public ResponseEntity<ClientAccountBuyerMembershipResponse> deactivateBuyerMembership(
			@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@PathVariable String membershipId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		ClientAccountBuyerMembershipMutation result = sales.deactivateBuyerMembership(context, id, membershipId,
				CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(result.accountVersion()))
				.body(mapper.buyerMembership(result.membership()));
	}

	@PutMapping("/{id}/sales-owner")
	@Operation(operationId = "assignClientAccountSalesOwner", summary = "Assign or transfer a Buyer account to an active Sales membership",
			description = "Company Owner with client.manage or Business Operations Manager may assign a Seller. The server "
					+ "validates the target active SALES membership in the same Tenant and Workspace, applies If-Match, and "
					+ "records the assignment or transfer through Business Traceability.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Sales owner assigned", headers = @Header(name = "ETag", description = "Current entity version", schema = @Schema(type = "string"))),
			@ApiResponse(responseCode = "400", description = "Invalid Sales membership identifier", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
			@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
			@ApiResponse(responseCode = "403", description = "Only Company Owner or Business Operations Manager may assign a Seller", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
			@ApiResponse(responseCode = "404", description = "Client Account or active same-scope Sales membership not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
			@ApiResponse(responseCode = "409", description = "The supplied account version is stale", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
			@ApiResponse(responseCode = "503", description = "Tenant business database is unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/NexaProblemDetail")))
	})
	public ResponseEntity<ClientAccountSalesOwnerAssignmentResponse> assignSalesOwner(
			@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody AssignClientAccountSalesOwnerRequest request) {
		var value = sales.assignSalesOwner(context, id, request.membershipId(),
				CustomerRelationshipHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.accountVersion()))
				.body(mapper.salesOwnerAssignment(value));
	}
}
