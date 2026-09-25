package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/** Application boundary for the authorized, current customer credit exposure projection. */
@Service
@Profile("!test")
public class CreditExposureApplicationService {
    private final CustomerAccountQuery customerAccounts;
    private final CreditExposureQuery exposures;

    public CreditExposureApplicationService(CustomerAccountQuery customerAccounts, CreditExposureQuery exposures) {
        this.customerAccounts = customerAccounts;
        this.exposures = exposures;
    }

    @Transactional(readOnly = true)
    public CreditExposureView read(CurrentAccessContext context, String clientAccountId, String currency) {
        context.requirePermission(PermissionKey.CLIENT_READ);
        String tenantId = context.tenantId().toString();
        String workspaceId = context.workspaceId().toString();
        var customer = customerAccounts.findReference(tenantId, workspaceId, clientAccountId)
                .orElseThrow(() -> new CreditReceivableOperationException("CLIENT_ACCOUNT_NOT_FOUND"));
        String normalizedCurrency = normalizeCurrency(currency);
        CreditExposureQuery.CreditExposureSnapshot snapshot = exposures.find(
                tenantId, workspaceId, customer.id(), normalizedCurrency);
        return new CreditExposureView(customer.id(), snapshot.currency(), snapshot.creditLimit(),
                snapshot.ledgerExposure(), snapshot.outstandingReceivables(), snapshot.reservedExposure(),
                snapshot.used(), snapshot.availableCredit(), snapshot.active(), Instant.now());
    }

    private static String normalizeCurrency(String currency) {
        String normalized = currency == null || currency.isBlank() ? "PEN" : currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be a three-letter code");
        return normalized;
    }

    public record CreditExposureView(String clientAccountId, String currency, BigDecimal creditLimit,
                                     BigDecimal ledgerExposure, BigDecimal outstandingReceivables,
                                     BigDecimal reservedExposure, BigDecimal used, BigDecimal availableCredit,
                                     boolean active, Instant asOf) { }
}
