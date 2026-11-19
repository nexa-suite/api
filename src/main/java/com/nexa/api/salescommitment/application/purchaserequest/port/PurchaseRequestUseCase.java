package com.nexa.api.salescommitment.application.purchaserequest.port;

import org.springframework.modulith.NamedInterface;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@NamedInterface("sales-public")
public interface PurchaseRequestUseCase extends PurchaseRequestQueries {
	PurchaseRequestView create(CurrentAccessContext context, String clientAccountId, String priority, LocalDate deliveryDate,
			String deliveryProfile, String paymentOption, String comment, List<RequestedLine> lines);
	PurchaseRequestView update(CurrentAccessContext context, String id, String priority, LocalDate deliveryDate,
			String deliveryProfile, String paymentOption, String comment, long version);
	PurchaseRequestView addLine(CurrentAccessContext context, String id, String catalogItemId, BigDecimal quantity, String unit, String notes, long version);
	PurchaseRequestView updateLine(CurrentAccessContext context, String id, String lineId, BigDecimal quantity, String notes, long version);
	PurchaseRequestView deleteLine(CurrentAccessContext context, String id, String lineId, long version);
	PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote, long version, String idempotencyKey);
	MaterialChangeProposalView proposeMaterialChange(CurrentAccessContext context, String id, long version,
			String reason, String priority, LocalDate deliveryDate, String deliveryProfile, String paymentOption,
			String comment, List<RequestedLine> lines, String idempotencyKey);
	PurchaseRequestView acceptMaterialChange(CurrentAccessContext context, String id, String proposalId,
			long version, String idempotencyKey);
	PurchaseRequestView rejectMaterialChange(CurrentAccessContext context, String id, String proposalId,
			long version, String idempotencyKey);
	@NamedInterface("sales-public")
	record RequestedLine(String catalogItemId, BigDecimal quantity, String unit, String notes) { }
}
