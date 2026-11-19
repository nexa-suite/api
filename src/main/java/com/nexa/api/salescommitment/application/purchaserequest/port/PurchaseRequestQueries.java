package com.nexa.api.salescommitment.application.purchaserequest.port;

import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestEventView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.util.List;

@NamedInterface("sales-public")
public interface PurchaseRequestQueries {
	SalesPage<PurchaseRequestView> list(CurrentAccessContext context, PurchaseRequestFilter filter);
	PurchaseRequestView detail(CurrentAccessContext context, String id);
	List<PurchaseRequestEventView> events(CurrentAccessContext context, String id);
	MaterialChangeProposalView currentMaterialChange(CurrentAccessContext context, String id);
	List<MaterialChangeProposalView> materialChangeHistory(CurrentAccessContext context, String id);
}
