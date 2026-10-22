package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.ListCatalogItemsUseCase;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogItemNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogHttpReadPort;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.mapper.CatalogResponseMapper;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.request.CatalogQueryParameters;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogItemDetailResponse;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogPageResponse;
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
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
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
	private final ObjectProvider<TenantCatalogHttpReadPort> tenantCatalogReads;

	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper) {
		this(listCatalogItems, getCatalogItem, responseMapper, null, null);
	}

	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper, ObjectProvider<CatalogClientAccountPort> clientAccounts) {
		this(listCatalogItems, getCatalogItem, responseMapper, clientAccounts, null);
	}

	@Autowired
	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper, ObjectProvider<CatalogClientAccountPort> clientAccounts,
			ObjectProvider<TenantCatalogHttpReadPort> tenantCatalogReads) {
		this.listCatalogItems = listCatalogItems;
		this.getCatalogItem = getCatalogItem;
		this.responseMapper = responseMapper;
		this.clientAccounts = clientAccounts;
		this.tenantCatalogReads = tenantCatalogReads;
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
	public CatalogPageResponse list(@RequestAttribute(value = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext", required = false) CurrentAccessContext context,
			@Valid @ModelAttribute CatalogQueryParameters parameters) {
		if (context == null) throw new AccessDeniedException("Catalog access context is required");
		return CatalogHttpSupport.tenantCatalogReadRequest(context, tenantCatalogReads,
				tenantRead -> responseMapper.toPage(tenantRead.list(context, parameters.toCriteria())));
	}

    public record FilterOptionResponse(String id, String name) { }
    public record FilterOptionsResponse(java.util.List<FilterOptionResponse> items, int page, int size, long total) { }

    @GetMapping("/filter-options")
    @Operation(operationId = "listVisibleCatalogFilterOptions", summary = "List active taxonomy choices from the authorized catalog")
    public FilterOptionsResponse filterOptions(@RequestAttribute(value = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
            @org.springframework.web.bind.annotation.RequestParam @Pattern(regexp = "brand|category|family|variant") String kind,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @jakarta.validation.constraints.Size(max = 120) String search,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID familyId) {
        if (context == null) throw new AccessDeniedException("Catalog access context is required");
		var choices = CatalogHttpSupport.tenantCatalogReadRequest(context, tenantCatalogReads,
				tenantRead -> tenantRead.filterOptions(context, kind, search, page, size, familyId));
        return new FilterOptionsResponse(choices.items().stream().map(value -> new FilterOptionResponse(value.id(), value.name())).toList(), choices.page(), choices.size(), choices.total());
    }

	@GetMapping("/{catalogItemId}")
	@Operation(operationId = "getCatalogItem", summary = "Get an active catalog item")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Catalog item returned"),
			@ApiResponse(responseCode = "400", description = "Invalid catalog item ID"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Access denied"),
			@ApiResponse(responseCode = "404", description = "Catalog item not found")})
	public CatalogItemDetailResponse getById(@RequestAttribute(value = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext", required = false) CurrentAccessContext context,
			@PathVariable @Pattern(regexp = CATALOG_ITEM_ID_PATTERN) String catalogItemId) {
		try {
			if (context == null) throw new AccessDeniedException("Catalog access context is required");
			return CatalogHttpSupport.tenantCatalogReadRequest(context, tenantCatalogReads,
					tenantRead -> responseMapper.toDetail(tenantRead.getByCatalogItemId(context, catalogItemId)));
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
		return CatalogHttpSupport.scope(context, clientAccounts);
	}
}
