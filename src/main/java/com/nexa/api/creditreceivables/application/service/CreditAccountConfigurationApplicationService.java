package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationUseCase;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationAuthorization;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Application rules for capability-gated, tenant-routed credit-account configuration. */
@Service
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class CreditAccountConfigurationApplicationService {
    private static final Pattern ETAG_VERSION = Pattern.compile("^\\\"(0|[1-9][0-9]*)\\\"$");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 120;
    private final CreditAccountConfigurationUseCase useCase;
    private final Clock clock;

    public CreditAccountConfigurationApplicationService(CreditAccountConfigurationUseCase useCase, Clock clock) {
        this.useCase = Objects.requireNonNull(useCase, "Credit configuration use case is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public CustomerAccountDirectoryQuery.Page candidates(CurrentAccessContext context, String search,
            String status, int page, int size) {
        CreditAccountConfigurationAuthorization.require(context);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Customer Account directory pagination is invalid");
        }
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        if (normalizedSearch != null && normalizedSearch.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("Customer Account directory search is too long");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !"ACTIVE".equals(normalizedStatus) && !"SUSPENDED".equals(normalizedStatus)) {
            throw new IllegalArgumentException("Customer Account directory status is invalid");
        }
        return useCase.candidates(context, normalizedSearch, normalizedStatus, page, size);
    }

    public CreditAccountConfigurationQuery.Snapshot read(CurrentAccessContext context, UUID customerAccountId,
            String currency) {
        CreditAccountConfigurationAuthorization.require(context);
        Objects.requireNonNull(customerAccountId, "Client Account id is required");
        return useCase.find(context, customerAccountId, normalizeCurrency(currency));
    }

    public CreditAccountConfigurationQuery.Snapshot configure(CurrentAccessContext context,
            UUID customerAccountId, ConfigurationRequest request, String ifMatch, String ifNoneMatch,
            String idempotencyKey) {
        CreditAccountConfigurationAuthorization.require(context);
        Objects.requireNonNull(customerAccountId, "Client Account id is required");
        Objects.requireNonNull(request, "Credit configuration request is required");
        String key = normalizeIdempotencyKey(idempotencyKey);
        boolean create = ifNoneMatch != null;
        Long expectedVersion;
        if (create) {
            if (!"*".equals(ifNoneMatch) || ifMatch != null) {
                throw new IllegalArgumentException("Create requires If-None-Match: * and no If-Match header");
            }
            expectedVersion = null;
        } else {
            if (ifMatch == null) throw error("PRECONDITION_REQUIRED");
            Matcher matcher = ETAG_VERSION.matcher(ifMatch.trim());
            if (!matcher.matches()) throw new IllegalArgumentException("If-Match must contain a quoted version");
            try {
                expectedVersion = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("If-Match version is invalid");
            }
        }
        String normalizedCurrency = normalizeCurrency(request.currency());
        BigDecimal limit = normalizeLimit(request.creditLimit());
        String requestHash = requestHash(customerAccountId, normalizedCurrency, limit, request.active(),
                create, expectedVersion);
        return useCase.configure(context, customerAccountId, normalizedCurrency, limit, request.active(),
                create, expectedVersion, key, requestHash, clock.instant());
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a three-letter code");
        }
        return normalized;
    }

    private static BigDecimal normalizeLimit(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 4 || value.precision() > 19) {
            throw new IllegalArgumentException("Credit limit must fit a non-negative 19,4 amount");
        }
        return value.setScale(4);
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw error("IDEMPOTENCY_KEY_REQUIRED");
        }
        return value.trim();
    }

    private static String requestHash(UUID customerAccountId, String currency, BigDecimal limit,
            boolean active, boolean create, Long expectedVersion) {
        String canonical = customerAccountId + "\n" + currency + "\n" + limit.toPlainString() + "\n"
                + active + "\n" + create + "\n" + (expectedVersion == null ? "" : expectedVersion);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CreditReceivableOperationException error(String code) {
        return new CreditReceivableOperationException(code);
    }

    public record ConfigurationRequest(String currency, BigDecimal creditLimit, boolean active) { }
}
