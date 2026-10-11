package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

@NamedInterface(value = "sales-public", propagate = false)
public interface ManualSalesOrderUseCase {
    ManualSalesOrderView create(CurrentAccessContext context, CreateManualSalesOrderCommand command,
                                 String idempotencyKey);
}
