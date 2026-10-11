package com.nexa.api.payments.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditPaymentCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableApplicationCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.PaymentSalesSource;
import com.nexa.api.payments.application.publicapi.TenantPaymentSession;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/** Creates a Payments session using the exact JDBC and transaction resources owned by Tenant routing. */
public interface TenantPaymentSessionFactory {
    TenantPaymentSession bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
            Dependencies dependencies);

    record Dependencies(ReceivableApplicationCommands receivableApplications,
            CreditPaymentCommands creditPayments, ReceivableCommands receivables,
            BusinessDocumentCommands documents, BusinessEvidenceQuery businessEvidence,
            CustomerAccountQuery customerAccounts, PaymentSalesSource salesOrders,
            CanonicalOutboxPort canonicalOutbox, ReceivablePaymentAccess receivableAccess) {
        public Dependencies {
            Objects.requireNonNull(receivableApplications);
            Objects.requireNonNull(creditPayments);
            Objects.requireNonNull(receivables);
            Objects.requireNonNull(documents);
            Objects.requireNonNull(businessEvidence);
            Objects.requireNonNull(customerAccounts);
            Objects.requireNonNull(salesOrders);
            Objects.requireNonNull(canonicalOutbox);
            Objects.requireNonNull(receivableAccess);
        }
    }
}
