package com.nexa.api.sales.presentation.salesorder;

import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.sales.presentation.SalesHttpHeaders;
import com.nexa.api.sales.presentation.salesorder.mapper.SalesOrderHttpMapper;
import com.nexa.api.sales.presentation.salesorder.response.FulfillmentCandidateResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderEventResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderPageResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderResponse;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@Profile("!test")
@Tag(name = "Sales Orders")
@SecurityRequirement(name = "bearerAuth")
public final class SalesOrderQueryController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final SalesOrderUseCase sales; private final SalesOrderHttpMapper mapper;
	public SalesOrderQueryController(SalesOrderUseCase sales, SalesOrderHttpMapper mapper) { this.sales = sales; this.mapper = mapper; }
	@GetMapping("/api/v1/sales-orders")
	public SalesOrderPageResponse list(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
			@RequestParam(defaultValue = "createdAt,desc") @Pattern(regexp = "(?i)(createdAt|updatedAt|number),(asc|desc)") String sort) {
		return mapper.page(sales.list(context, new SalesOrderFilter(status, page, size, sort)));
	}
	@GetMapping("/api/v1/sales-orders/{id}")
	public org.springframework.http.ResponseEntity<SalesOrderResponse> detail(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) {
		var value = sales.detail(context, id); return org.springframework.http.ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.response(value));
	}
	@GetMapping("/api/v1/sales-orders/{id}/events")
	public List<SalesOrderEventResponse> events(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) { return sales.events(context, id).stream().map(mapper::event).toList(); }
	@GetMapping("/api/v1/order-fulfillment-candidates")
	public com.nexa.api.sales.presentation.salesorder.response.FulfillmentCandidatePageResponse candidates(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
		var value = sales.fulfillmentCandidates(context, new SalesOrderFilter("CONFIRMED", page, size, "createdAt,desc"));
		return new com.nexa.api.sales.presentation.salesorder.response.FulfillmentCandidatePageResponse(value.items().stream().map(mapper::candidate).toList(), value.page(), value.size(), value.total());
	}
}
