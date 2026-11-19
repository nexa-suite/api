package com.nexa.api.salescommitment.application.model;

import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestQueries;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderQueries;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.util.function.Function;

@NamedInterface("sales-public")
public interface SalesCommitmentQueryRunner {
    <T> T purchaseRequests(CurrentAccessContext context, Function<PurchaseRequestQueries, T> query);

    <T> T salesOrders(CurrentAccessContext context, Function<SalesOrderQueries, T> query);
}
