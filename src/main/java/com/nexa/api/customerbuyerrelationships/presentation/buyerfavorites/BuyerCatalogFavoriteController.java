package com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites;

import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerCatalogFavoriteUseCase;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoritePage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoriteView;
import com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response.BuyerCatalogFavoritePageResponse;
import com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response.BuyerCatalogFavoriteResponse;
import com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response.CurrentCatalogOfferResponse;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

/** HTTP boundary for the current Buyer membership's personal Catalog offer preferences. */
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.catalog-favorites", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/buyer/favorites")
@Tag(name = "Buyer catalog favorites")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerCatalogFavoriteController {
    private static final String ACCESS =
            "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final BuyerCatalogFavoriteUseCase favorites;

    public BuyerCatalogFavoriteController(BuyerCatalogFavoriteUseCase favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    @Operation(operationId = "listCurrentBuyerCatalogFavorites",
            description = "Lists this verified Buyer membership's saved offer SKUs. Each offer is resolved from the "
                    + "current visible Catalog; saved SKUs that are no longer active or visible remain in the page "
                    + "with currentOffer null so the Buyer can identify and remove the saved association. "
                    + "Pagination counts stored preferences.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current Buyer favorites returned"),
            @ApiResponse(responseCode = "400", description = "Invalid page or size",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "403", description = "An active Buyer relationship is required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail")))
    })
    public BuyerCatalogFavoritePageResponse list(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return response(favorites.list(context, page, size));
    }

    @PutMapping("/{sellableSkuId}")
    @Operation(operationId = "addCurrentBuyerCatalogFavorite",
            description = "Idempotently saves one currently active and Buyer-visible Catalog sellable SKU offer "
                    + "for the verified Buyer membership. Catalog fields are resolved live and are not snapshotted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current Buyer favorite returned"),
            @ApiResponse(responseCode = "400", description = "Invalid sellable SKU identifier",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "403", description = "An active Buyer relationship is required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "404", description = "No current Buyer-visible offer exists for the SKU",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail")))
    })
    public BuyerCatalogFavoriteResponse add(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID sellableSkuId) {
        return favorites.add(context, sellableSkuId).map(BuyerCatalogFavoriteController::response)
                .orElseThrow(() -> new ApiResourceNotFoundException("catalog offer"));
    }

    @DeleteMapping("/{sellableSkuId}")
    @Operation(operationId = "removeCurrentBuyerCatalogFavorite",
            description = "Idempotently removes this Buyer membership's saved SKU association. Removal remains "
                    + "available when the Catalog offer is no longer active or visible.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Favorite removed or already absent"),
            @ApiResponse(responseCode = "400", description = "Invalid sellable SKU identifier",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "403", description = "An active Buyer relationship is required",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "Tenant business database is unavailable",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/NexaProblemDetail")))
    })
    public ResponseEntity<Void> remove(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID sellableSkuId) {
        favorites.remove(context, sellableSkuId);
        return ResponseEntity.noContent().build();
    }

    private static BuyerCatalogFavoritePageResponse response(BuyerCatalogFavoritePage page) {
        int totalPages = (int) ((page.totalItems() + page.size() - 1) / page.size());
        return new BuyerCatalogFavoritePageResponse(page.items().stream()
                .map(BuyerCatalogFavoriteController::response).toList(), page.page(), page.size(),
                page.totalItems(), totalPages);
    }

    private static BuyerCatalogFavoriteResponse response(BuyerCatalogFavoriteView favorite) {
        return new BuyerCatalogFavoriteResponse(favorite.sellableSkuId(), favorite.favoritedAt(),
                favorite.currentOffer() == null ? null : CurrentCatalogOfferResponse.from(favorite.currentOffer()));
    }
}
