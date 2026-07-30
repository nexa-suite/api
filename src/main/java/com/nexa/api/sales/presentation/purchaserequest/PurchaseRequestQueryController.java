package com.nexa.api.sales.presentation.purchaserequest;

import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.sales.presentation.SalesHttpHeaders;
import com.nexa.api.sales.presentation.purchaserequest.mapper.PurchaseRequestHttpMapper;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestDetailResponse;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestPageResponse;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/purchase-requests")
@Profile("!test")
@Validated
@Tag(name = "Purchase Requests")
@SecurityRequirement(name = "bearerAuth")
public class PurchaseRequestQueryController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final PurchaseRequestUseCase sales;
	private final PurchaseRequestHttpMapper mapper;
	public PurchaseRequestQueryController(PurchaseRequestUseCase sales, PurchaseRequestHttpMapper mapper) { this.sales = sales; this.mapper = mapper; }

	@GetMapping
	public PurchaseRequestPageResponse list(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestParam(required = false) @Pattern(regexp = "DRAFT|SUBMITTED|IN_REVIEW|NEEDS_ADJUSTMENT|APPROVED|REJECTED|CANCELLED|CONVERTED_TO_ORDER") String status,
			@RequestParam(required = false) @Pattern(regexp = "NORMAL|HIGH|URGENT") String priority, @RequestParam(required = false) String search,
			@RequestParam(required = false) LocalDate createdFrom, @RequestParam(required = false) LocalDate createdTo,
			@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
			@RequestParam(defaultValue = "createdAt,desc") @Pattern(regexp = "(createdAt|updatedAt),(asc|desc)") String sort) {
		return mapper.page(sales.list(context, new PurchaseRequestFilter(status, priority, search, createdFrom, createdTo, page, size, sort)));
	}

	@GetMapping("/{id}")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Purchase Request returned", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "404", description = "Purchase Request not found")})
	public ResponseEntity<PurchaseRequestDetailResponse> detail(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) {
		var value = sales.detail(context, id); return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}
}
