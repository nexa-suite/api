package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogmanagement.application.port.in.ListCatalogItemsUseCase;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogClientAccountPort;
import com.nexa.api.catalogmanagement.application.exception.CatalogItemNotFoundException;
import com.nexa.api.shared.presentation.error.ApiResourceNotFoundException;
import com.nexa.api.catalogmanagement.presentation.rest.mapper.CatalogResponseMapper;
import com.nexa.api.catalogmanagement.presentation.rest.request.CatalogQueryParameters;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogItemDetailResponse;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.security.access.AccessDeniedException;

@RestController
@RequestMapping("/api/v1/catalog-items")
@Validated
@Tag(name = "Catalog Management")
public class CatalogQueryController {
	private static final String CATALOG_ITEM_ID_PATTERN = "(?i)CAT-[A-Z0-9-]{1,63}";
	private final ListCatalogItemsUseCase listCatalogItems;
	private final GetCatalogItemUseCase getCatalogItem;
	private final CatalogResponseMapper responseMapper;
	private final ObjectProvider<CatalogClientAccountPort> clientAccounts;

	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper) {
		this(listCatalogItems, getCatalogItem, responseMapper, null);
	}

	@Autowired
	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper, ObjectProvider<CatalogClientAccountPort> clientAccounts) {
		this.listCatalogItems = listCatalogItems;
		this.getCatalogItem = getCatalogItem;
		this.responseMapper = responseMapper;
		this.clientAccounts = clientAccounts;
	}

	/** Compatibility entry point for application-level callers; HTTP routes always require an access context. */
	public CatalogPageResponse list(CatalogQueryParameters parameters) {
		return responseMapper.toPage(listCatalogItems.list(parameters.toCriteria()));
	}

	@GetMapping
	@Operation(operationId = "listCatalogItems", summary = "List active catalog items")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Catalog page returned"),
			@ApiResponse(responseCode = "400", description = "Invalid query parameters"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Access denied")})
	public CatalogPageResponse list(@RequestAttribute(value = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext", required = false) CurrentAccessContext context,
			@Valid @ModelAttribute CatalogQueryParameters parameters) {
		if (context == null) throw new AccessDeniedException("Catalog access context is required");
		CatalogScope scope = scope(context);
		return responseMapper.toPage(listCatalogItems.list(scope, parameters.toCriteria()));
	}

	@GetMapping("/{catalogItemId}")
	@Operation(operationId = "getCatalogItem", summary = "Get an active catalog item")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Catalog item returned"),
			@ApiResponse(responseCode = "400", description = "Invalid catalog item ID"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Access denied"),
			@ApiResponse(responseCode = "404", description = "Catalog item not found")})
	public CatalogItemDetailResponse getById(@RequestAttribute(value = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext", required = false) CurrentAccessContext context,
			@PathVariable @Pattern(regexp = CATALOG_ITEM_ID_PATTERN) String catalogItemId) {
		try {
			if (context == null) throw new AccessDeniedException("Catalog access context is required");
			CatalogScope scope = scope(context);
			return responseMapper.toDetail(getCatalogItem.getByCatalogItemId(scope, catalogItemId));
		} catch (CatalogItemNotFoundException exception) {
			throw new ApiResourceNotFoundException("catalog item");
		}
	}

	/** Compatibility entry point for application-level callers; HTTP routes always require an access context. */
	public CatalogItemDetailResponse getById(String catalogItemId) {
		try {
			return responseMapper.toDetail(getCatalogItem.getByCatalogItemId(catalogItemId));
		} catch (CatalogItemNotFoundException exception) {
			throw new ApiResourceNotFoundException("catalog item");
		}
	}

	private CatalogScope scope(CurrentAccessContext context) {
		boolean buyer = context.hasRole(MembershipRole.BUYER);
		java.util.UUID clientAccountId = null;
		if (buyer && clientAccounts != null) {
			CatalogClientAccountPort resolver = clientAccounts.getIfAvailable();
			if (resolver != null) {
				CatalogClientAccountPort.ClientAccountProfile profile = resolver.findProfileForMembership(
						context.tenantId().value(), context.workspaceId().value(), context.membershipId().value()).orElse(null);
				if (profile != null) {
					clientAccountId = profile.id();
					return new CatalogScope(context.tenantId().value(), context.workspaceId().value(), true,
							clientAccountId, profile.segment(), profile.buyerTier());
				}
			}
		}
		return new CatalogScope(context.tenantId().value(), context.workspaceId().value(), buyer, clientAccountId);
	}
}
