package com.nexa.api.customerbuyerrelationships.presentation.clientaccount;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.presentation.CustomerRelationshipHttpHeaders;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.mapper.ClientAccountHttpMapper;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.AssociateBuyerMembershipRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.CreateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.UpdateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountDetailResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountPageResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.headers.Header;
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
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Buyer client account returned", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "404", description = "Buyer client account not found")})
	public ResponseEntity<ClientAccountDetailResponse> buyerDetail(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context) {
		var value = sales.buyerDetail(context); return ResponseEntity.ok().eTag(CustomerRelationshipHttpHeaders.etag(value.version())).body(mapper.detail(value));
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
}
