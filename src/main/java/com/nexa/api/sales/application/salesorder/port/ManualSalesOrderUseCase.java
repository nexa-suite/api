package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface ManualSalesOrderUseCase {
    ManualSalesOrderView create(CurrentAccessContext context, CreateManualSalesOrderCommand command,
                                 String idempotencyKey);
}
