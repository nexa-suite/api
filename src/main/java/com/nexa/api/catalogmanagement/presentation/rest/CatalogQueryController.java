package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogmanagement.application.port.in.ListCatalogItemsUseCase;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog-items")
@Validated
@Tag(name = "Catalog Management")
public class CatalogQueryController {
	private static final String CATALOG_ITEM_ID_PATTERN = "(?i)CAT-[A-Z0-9-]{1,63}";
	private final ListCatalogItemsUseCase listCatalogItems;
	private final GetCatalogItemUseCase getCatalogItem;
	private final CatalogResponseMapper responseMapper;

	public CatalogQueryController(ListCatalogItemsUseCase listCatalogItems, GetCatalogItemUseCase getCatalogItem,
			CatalogResponseMapper responseMapper) {
		this.listCatalogItems = listCatalogItems;
		this.getCatalogItem = getCatalogItem;
		this.responseMapper = responseMapper;
	}

	@GetMapping
	@Operation(summary = "List active catalog items")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Catalog page returned"),
			@ApiResponse(responseCode = "400", description = "Invalid query parameters"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Access denied")})
	public CatalogPageResponse list(@Valid @ModelAttribute CatalogQueryParameters parameters) {
		return responseMapper.toPage(listCatalogItems.list(parameters.toCriteria()));
	}

	@GetMapping("/{catalogItemId}")
	@Operation(summary = "Get an active catalog item")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Catalog item returned"),
			@ApiResponse(responseCode = "400", description = "Invalid catalog item ID"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Access denied"),
			@ApiResponse(responseCode = "404", description = "Catalog item not found")})
	public CatalogItemDetailResponse getById(@PathVariable @Pattern(regexp = CATALOG_ITEM_ID_PATTERN) String catalogItemId) {
		try {
			return responseMapper.toDetail(getCatalogItem.getByCatalogItemId(catalogItemId));
		} catch (CatalogItemNotFoundException exception) {
			throw new ApiResourceNotFoundException("catalog item");
		}
	}
}
