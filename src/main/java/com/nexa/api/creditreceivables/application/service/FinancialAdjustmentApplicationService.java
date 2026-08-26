package com.nexa.api.creditreceivables.application.service;

import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** BC-07 application boundary for post-payment obligation corrections. */
@Service
@Profile("!test")
public final class FinancialAdjustmentApplicationService {
    private static final Set<String> POST_PAYMENT_SOURCES = Set.of("SALES_ORDER_CANCELLATION", "SALES_ORDER_REDUCTION");

    private final FinancialAdjustmentCommands commands;
    private final Clock clock;

    public FinancialAdjustmentApplicationService(FinancialAdjustmentCommands commands, Clock clock) {
        this.commands = Objects.requireNonNull(commands, "Financial adjustment commands are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public FinancialAdjustmentCommands.Result postPostPayment(CurrentAccessContext context, UUID receivableId,
                                                               long expectedReceivableVersion, String idempotencyKey,
                                                               PostPaymentCommand command) {
        context.requirePermission(PermissionKey.CLIENT_CREDIT_MANAGE);
        if (receivableId == null || idempotencyKey == null || idempotencyKey.isBlank() || command == null) {
            throw error("INVALID_REQUEST");
        }
        if (command.salesOrderId() == null || !command.salesOrderId().equals(command.sourceId())) {
            throw error("ADJUSTMENT_SOURCE_INVALID");
        }
        String sourceType = normalize(command.sourceType());
        if (!POST_PAYMENT_SOURCES.contains(sourceType)) throw error("ADJUSTMENT_SOURCE_INVALID");
        String effect = command.effect() == null ? "DECREASE" : normalize(command.effect());
        String kind = command.adjustmentKind() == null ? "CORRECTION" : normalize(command.adjustmentKind());
        if (!"DECREASE".equals(effect) || !("DECREASE".equals(kind) || "CORRECTION".equals(kind))) {
            throw error("ADJUSTMENT_EFFECT_INVALID");
        }
        String currency = normalize(command.currency());
        String obligationType = command.obligationType() == null ? "CUSTOMER_CREDIT" : normalize(command.obligationType());
        if (!Set.of("REFUND", "CUSTOMER_CREDIT").contains(obligationType)) {
            throw error("REFUND_CREDIT_OBLIGATION_TYPE_INVALID");
        }
        String key = idempotencyKey.trim();
        String reason = command.reason().trim();
        String requestHash = sha256(receivableId + "|" + command.salesOrderId() + "|" + sourceType + "|"
                + kind + "|" + effect + "|" + command.amount().stripTrailingZeros().toPlainString() + "|"
                + currency + "|" + reason + "|" + obligationType + "|" + expectedReceivableVersion);
        return commands.post(new FinancialAdjustmentCommands.Request(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
                context.userId().value(), receivableId, command.salesOrderId(), null, command.sourceId(), kind, effect,
                command.amount(), currency, reason, sourceType, key, requestHash, obligationType,
                expectedReceivableVersion, clock.instant()));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw error("INVALID_REQUEST");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CreditReceivableOperationException error(String code) {
        return new CreditReceivableOperationException(code);
    }

    public record PostPaymentCommand(UUID salesOrderId, UUID sourceId, String sourceType,
                                     String adjustmentKind, String effect, BigDecimal amount,
                                     String currency, String reason, String obligationType) {
        public PostPaymentCommand {
            if (salesOrderId == null || sourceId == null || sourceType == null || sourceType.isBlank()
                    || amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()
                    || reason == null || reason.isBlank() || reason.length() > 2000) {
                throw new IllegalArgumentException("Post-payment adjustment request is incomplete");
            }
        }
    }
}
