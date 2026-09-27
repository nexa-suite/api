package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.application.publicapi.PaymentSalesSource;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

/** Same-process composition uses owner contracts and the caller's local transaction. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CommercialFinancialSourceConfiguration {
    @Bean
    FinancialAdjustmentSource financialAdjustmentSource(SalesOrderFulfillmentQuery sales,
                                                         PaymentConfirmationQuery payments) {
        return new FinancialAdjustmentSource() {
            @Override
            public Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                var order = sales.getForUpdate(tenantId, workspaceId, salesOrderId);
                return new Snapshot(order.status(), order.currency(), order.total());
            }

            @Override
            public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return payments.hasSuccessfulPayment(tenantId, workspaceId, salesOrderId);
            }
        };
    }

    @Bean
    PaymentSalesSource paymentSalesSource(SalesOrderFulfillmentQuery sales) {
        return new PaymentSalesSource() {
            @Override
            public Snapshot claimPayableSubject(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                var order = sales.getForUpdate(tenantId, workspaceId, salesOrderId);
                return new Snapshot(order.clientAccountId(), order.total(), order.currency(), order.status(), order.paymentOption());
            }

            @Override
            public boolean exists(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                try {
                    sales.get(tenantId, workspaceId, salesOrderId);
                    return true;
                } catch (CommercialBusinessException exception) {
                    if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
                    return false;
                }
            }
        };
    }
}
