package com.nexa.api.salescommitment.application.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.salescommitment.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.application.model.FieldPurchaseRequestModels.Command;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.domain.publicapi.SalesInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/** One atomic field submission reuses the accepted draft and submission workflow. */
@Service
@Profile("!test")
public class FieldPurchaseRequestSubmissionService {
    private static final String OPERATION = "purchase-request-field-submission";
    private final PurchaseRequestUseCase requests;
    private final CustomerAccountQuery accounts;
    private final CatalogItemSnapshotLookupPort catalog;
    private final IdempotencyPersistencePort idempotency;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FieldPurchaseRequestSubmissionService(PurchaseRequestUseCase requests, CustomerAccountQuery accounts,
            CatalogItemSnapshotLookupPort catalog, IdempotencyPersistencePort idempotency, ObjectMapper mapper, Clock clock) {
        this.requests = requests; this.accounts = accounts; this.catalog = catalog;
        this.idempotency = idempotency; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public PurchaseRequestView submit(CurrentAccessContext context, Command command, String key) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Field submission requires internal sales authority");
        context.requirePermission(Permission.SALES_WRITE);
        if (key == null || key.isBlank() || key.length() > 160) throw new IdempotencyKeyRequiredException();
        String tenant = context.tenantId().toString();
        String workspace = context.workspaceId().toString();
        String actor = context.membershipId().toString();
        if (command.clientAccountId() == null || command.clientAccountId().isBlank()) throw new SalesInvariantViolation("Client Account is required");
        String account = accounts.findReference(tenant, workspace, command.clientAccountId())
                .filter(value -> "ACTIVE".equals(value.status())).map(value -> value.id())
                .orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
        if (command.lines().isEmpty() || command.lines().size() > 100) throw new SalesInvariantViolation("At least one permitted product is required");
        String commandHash = hash(serialize(command));
        idempotency.lock(tenant, workspace, actor, OPERATION, key);
        var prior = idempotency.find(tenant, workspace, actor, OPERATION, key, commandHash);
        if (prior.isPresent()) {
            // Resolve through the current authorized resource read before exposing the frozen outcome.
            requests.detail(context, prior.get().resourceId());
            try { return mapper.readValue(prior.get().responseJson(), PurchaseRequestView.class); }
            catch (Exception exception) { throw new IllegalStateException("Field submission result is unavailable", exception); }
        }
        for (var line : command.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.expectedUnitPrice() == null ||
                    line.expectedCurrency() == null) throw new SalesInvariantViolation("Current product terms are required");
            var item = catalog.findActive(line.catalogItemId(), context.tenantId().value(), context.workspaceId().value(), account, line.quantity())
                    .orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
            if (item.price().amount().compareTo(line.expectedUnitPrice()) != 0 ||
                    !item.price().currency().equals(line.expectedCurrency())) throw new SalesConcurrencyConflictException();
        }
        var lines = command.lines().stream().map(line -> new PurchaseRequestUseCase.RequestedLine(
                line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList();
        var draft = requests.create(context, account, command.priority(), command.requestedDeliveryDate(),
                command.deliveryProfileSnapshot(), command.paymentOption(), command.comment(), lines);
        if (draft.lines().size() != command.lines().size() || draft.lines().stream().anyMatch(snapshot ->
                command.lines().stream().noneMatch(line -> snapshot.catalogItemId().equals(line.catalogItemId()) &&
                        snapshot.quantity().compareTo(line.quantity()) == 0 &&
                        snapshot.unitPriceAmount().compareTo(line.expectedUnitPrice()) == 0 &&
                        snapshot.unitPriceCurrency().equals(line.expectedCurrency())))) {
            throw new SalesConcurrencyConflictException();
        }
        var result = requests.transition(context, draft.id(), "submit", null, draft.version(),
                "field-submit-" + hash(key));
        idempotency.save(tenant, workspace, actor, OPERATION, key, result.id(), result.version(),
                UUID.randomUUID(), clock.millis(), commandHash, serialize(result));
        return result;
    }

    private String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Field submission encoding failed", exception); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
