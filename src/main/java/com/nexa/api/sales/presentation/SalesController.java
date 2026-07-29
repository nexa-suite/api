package com.nexa.api.sales.presentation;

import com.nexa.api.sales.application.model.*;
import com.nexa.api.sales.application.port.in.SalesUseCase;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@Profile("!test")
public class SalesController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final SalesUseCase sales;
	public SalesController(SalesUseCase sales) { this.sales = sales; }

	@GetMapping("/api/v1/client-accounts")
	public SalesPage<ClientAccountView> accounts(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestParam(required=false) String search, @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) { return sales.clientAccounts(context, search, status, page, size); }
	@GetMapping("/api/v1/client-accounts/{id}")
	public ResponseEntity<ClientAccountView> account(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) { var value=sales.clientAccount(context,id); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PostMapping("/api/v1/client-accounts")
	public ResponseEntity<ClientAccountView> createAccount(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @RequestBody ClientAccountView body) { var value=sales.createClientAccount(context,body); return ResponseEntity.status(201).eTag(etag(value.version())).body(value); }
	@PatchMapping("/api/v1/client-accounts/{id}")
	public ResponseEntity<ClientAccountView> updateAccount(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch, @RequestBody ClientAccountView body) { var value=sales.updateClientAccount(context,id,body,version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PostMapping("/api/v1/client-accounts/{id}/activations")
	public ResponseEntity<ClientAccountView> activateAccount(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch) { var value=sales.changeClientAccountStatus(context,id,"ACTIVE",version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PostMapping("/api/v1/client-accounts/{id}/suspensions")
	public ResponseEntity<ClientAccountView> suspendAccount(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch) { var value=sales.changeClientAccountStatus(context,id,"SUSPENDED",version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PutMapping("/api/v1/client-accounts/{id}/buyer-membership")
	public ResponseEntity<ClientAccountView> associateBuyer(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch, @RequestBody BuyerMembership body) { var value=sales.associateBuyer(context,id,body.membershipId(),version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@GetMapping("/api/v1/purchase-requests")
	public SalesPage<PurchaseRequestView> requests(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @RequestParam(required=false) String status, @RequestParam(required=false) String priority, @RequestParam(required=false) String search, @RequestParam(required=false) LocalDate createdFrom, @RequestParam(required=false) LocalDate createdTo, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size, @RequestParam(defaultValue="createdAt,desc") String sort) { return sales.purchaseRequests(context,new PurchaseRequestFilter(status,priority,search,createdFrom,createdTo,page,size,sort)); }
	@GetMapping("/api/v1/purchase-requests/{id}")
	public ResponseEntity<PurchaseRequestView> request(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id) { var value=sales.purchaseRequest(context,id); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PostMapping("/api/v1/purchase-requests")
	public ResponseEntity<PurchaseRequestView> createRequest(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @RequestBody RequestDraft body) { var lines=body.lines()==null?List.<com.nexa.api.sales.application.port.in.SalesUseCase.RequestedLine>of():body.lines().stream().map(line->new com.nexa.api.sales.application.port.in.SalesUseCase.RequestedLine(line.catalogItemId(),line.quantity(),line.unit(),line.notes())).toList(); var value=sales.createPurchaseRequest(context,body.clientAccountId(),body.priority(),body.requestedDeliveryDate(),body.deliveryProfileSnapshot(),body.paymentOption(),body.comment(),lines); return ResponseEntity.status(201).eTag(etag(value.version())).body(value); }
	@PatchMapping("/api/v1/purchase-requests/{id}")
	public ResponseEntity<PurchaseRequestView> updateRequest(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch, @RequestBody RequestPatch body) { var value=sales.updatePurchaseRequest(context,id,body.priority(),body.requestedDeliveryDate(),body.deliveryProfileSnapshot(),body.paymentOption(),body.comment(),version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PostMapping("/api/v1/purchase-requests/{id}/lines")
	public ResponseEntity<PurchaseRequestView> addLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name="If-Match",required=false) String ifMatch, @RequestBody LineCommand body) { var value=sales.addLine(context,id,body.catalogItemId(),body.quantity(),body.unit(),body.notes(),version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@PatchMapping("/api/v1/purchase-requests/{id}/lines/{lineId}")
	public ResponseEntity<PurchaseRequestView> updateLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @PathVariable String lineId, @RequestHeader(name="If-Match",required=false) String ifMatch, @RequestBody LinePatch body) { var value=sales.updateLine(context,id,lineId,body.quantity(),body.notes(),version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
	@DeleteMapping("/api/v1/purchase-requests/{id}/lines/{lineId}")
	public ResponseEntity<PurchaseRequestView> deleteLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @PathVariable String lineId, @RequestHeader(name="If-Match",required=false) String ifMatch) { var value=sales.deleteLine(context,id,lineId,version(ifMatch)); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PostMapping("/api/v1/purchase-requests/{id}/submissions") public ResponseEntity<PurchaseRequestView> submit(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v,@RequestHeader(name="Idempotency-Key",required=false) String key){return transition(c,id,"submit",null,v,key);}
	@PostMapping("/api/v1/purchase-requests/{id}/reviews") public ResponseEntity<PurchaseRequestView> review(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v,@RequestBody(required=false) ReviewCommand b){return transition(c,id,"start-review",b==null?null:b.reviewNote(),v,null);}
	@PostMapping("/api/v1/purchase-requests/{id}/adjustment-requests") public ResponseEntity<PurchaseRequestView> adjustment(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v,@RequestBody(required=false) ReviewCommand b){return transition(c,id,"request-adjustment",b==null?null:b.reviewNote(),v,null);}
	@PostMapping("/api/v1/purchase-requests/{id}/approvals") public ResponseEntity<PurchaseRequestView> approve(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v,@RequestBody(required=false) ReviewCommand b){return transition(c,id,"approve",b==null?null:b.reviewNote(),v,null);}
	@PostMapping("/api/v1/purchase-requests/{id}/rejections") public ResponseEntity<PurchaseRequestView> reject(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v,@RequestBody(required=false) ReviewCommand b){return transition(c,id,"reject",b==null?null:b.reviewNote(),v,null);}
	@PostMapping("/api/v1/purchase-requests/{id}/cancellations") public ResponseEntity<PurchaseRequestView> cancel(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext c,@PathVariable String id,@RequestHeader(name="If-Match",required=false) String v){return transition(c,id,"cancel",null,v,null);}

	private ResponseEntity<PurchaseRequestView> transition(CurrentAccessContext c,String id,String action,String note,String ifMatch,String key){var value=sales.transition(c,id,action,note,version(ifMatch),key);return ResponseEntity.ok().eTag(etag(value.version())).body(value);}
	private static long version(String value){if(value==null||value.isBlank())throw new com.nexa.api.sales.application.exception.SalesPreconditionRequiredException();try{return Long.parseLong(value.replace("\"","").trim());}catch(NumberFormatException e){throw new com.nexa.api.sales.application.exception.SalesPreconditionRequiredException();}}
	private static String etag(long v){return "\""+v+"\"";}
	public record BuyerMembership(String membershipId){}
	public record RequestDraft(String clientAccountId,String priority,LocalDate requestedDeliveryDate,String deliveryProfileSnapshot,String paymentOption,String comment,List<LineCommand> lines){}
	public record RequestPatch(String priority,LocalDate requestedDeliveryDate,String deliveryProfileSnapshot,String paymentOption,String comment){}
	public record LineCommand(String catalogItemId,BigDecimal quantity,String unit,String notes){}
	public record LinePatch(BigDecimal quantity,String notes){}
	public record ReviewCommand(String reviewNote){}
}
