package com.nexa.api.sales.application.workflow;

import com.nexa.api.sales.application.port.out.ClientAccountAddressPort;
import com.nexa.api.sales.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.application.port.out.WarehouseReferencePort;
import com.nexa.api.sales.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.address.Address;
import com.nexa.api.sales.domain.model.clientaccount.ClientAccountAddress;
import com.nexa.api.sales.domain.model.commercial.CommercialSnapshot;
import com.nexa.api.sales.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.sales.domain.model.delivery.DeliverySnapshot;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import com.nexa.api.sales.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.sales.domain.model.payment.PaymentSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PriceSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestLine;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestLineId;
import com.nexa.api.sales.domain.model.purchaserequest.RequestedQuantity;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves foreign facts once and turns them into immutable Sales-owned snapshots.
 * It deliberately depends on ACL ports instead of Warehouse, Catalog or IAM entities.
 */
public final class SalesSnapshotAssembler {
    private final ClientAccountCommercialPort commercial;
    private final ClientAccountAddressPort addresses;
    private final WarehouseReferencePort warehouses;
    private final PeruGeographyPersistencePort geography;
    private final MapRoutingPort maps;
    private final com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort catalog;
    private final com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort sellableSkus;

    public SalesSnapshotAssembler(ClientAccountCommercialPort commercial, ClientAccountAddressPort addresses,
                                  WarehouseReferencePort warehouses, PeruGeographyPersistencePort geography,
                                  MapRoutingPort maps,
                                  com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort catalog) {
        this(commercial, addresses, warehouses, geography, maps, catalog, null);
    }

    public SalesSnapshotAssembler(ClientAccountCommercialPort commercial, ClientAccountAddressPort addresses,
                                  WarehouseReferencePort warehouses, PeruGeographyPersistencePort geography,
                                  MapRoutingPort maps,
                                  com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort catalog,
                                  com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort sellableSkus) {
        this.commercial = Objects.requireNonNull(commercial);
        this.addresses = Objects.requireNonNull(addresses);
        this.warehouses = Objects.requireNonNull(warehouses);
        this.geography = Objects.requireNonNull(geography);
        this.maps = Objects.requireNonNull(maps);
        this.catalog = Objects.requireNonNull(catalog);
        this.sellableSkus = sellableSkus;
    }

    public BuyerAssembly buyer(CurrentAccessContext context, String clientAccountId, String addressId,
                               Address manualAddress, LocalDate requestedDate, String deliveryNotes, String comments,
                               String warehouseId, String routeProvider, PaymentOption paymentOption,
                               List<com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand.Line> requestedLines) {
        String accountId = resolveAccountIdForBuyer(context, clientAccountId);
        List<PurchaseRequestLine> lines = buyerLines(requestedLines, context);
        BigDecimal total = total(lines);
        BaseAssembly base = base(context, accountId, addressId, manualAddress, requestedDate, deliveryNotes,
                warehouseId, routeProvider, paymentOption, total, lines.isEmpty() ? "PEN" : lines.getFirst().catalogItem().price().currency());
        return new BuyerAssembly(lines, new com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot(
                base.delivery(), base.commercial(), base.payment(), Instant.now(), comments));
    }

    public ManualAssembly manual(CurrentAccessContext context, String clientAccountId, String addressId,
                                 Address manualAddress, LocalDate requestedDate, String deliveryNotes, String orderNotes,
                                 String warehouseId, String routeProvider, PaymentOption paymentOption,
                                 com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority priority,
                                 String currency, List<com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand.Line> requestedLines) {
        String accountId = resolveAccountIdForSales(context, clientAccountId);
        List<com.nexa.api.sales.domain.model.salesorder.SalesOrderLine> lines = manualLines(requestedLines, currency, context);
        BigDecimal total = lines.stream().map(com.nexa.api.sales.domain.model.salesorder.SalesOrderLine::lineSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BaseAssembly base = base(context, accountId, addressId, manualAddress, requestedDate, deliveryNotes,
                warehouseId, routeProvider, paymentOption, total, normalizeCurrency(currency));
        return new ManualAssembly(lines, priority, new com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderSnapshot(
                base.delivery(), base.commercial(), base.payment(), Instant.now(), orderNotes));
    }

    private BaseAssembly base(CurrentAccessContext context, String accountId, String addressId, Address manualAddress,
                              LocalDate requestedDate, String deliveryNotes, String warehouseId, String routeProvider,
                              PaymentOption paymentOption, BigDecimal total, String currency) {
        if (requestedDate == null) throw new SalesInvariantViolation("Requested delivery date is required");
        ClientAccountCommercialPort.ClientAccountCommercialProfile profile = commercial.find(scope(context), workspace(context), accountId)
                .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account"));
        CommercialSnapshot commercialSnapshot = new CommercialSnapshot(profile.id(), profile.businessName(), profile.commercialName(),
                profile.taxIdentifier(), profile.credit(), profile.paymentTerms(), profile.active());
        DeliveryAddressSnapshot addressSnapshot = resolveAddress(context, accountId, addressId, manualAddress);
        WarehouseReferencePort.WarehouseReference warehouse = warehouseId == null || warehouseId.isBlank()
                ? warehouses.findPrimary(scope(context), workspace(context))
                .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("warehouse"))
                : warehouses.findActive(scope(context), workspace(context), warehouseId)
                .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("warehouse"));
        WarehouseSnapshot warehouseSnapshot = new WarehouseSnapshot(warehouse.id(), warehouse.code(), warehouse.name(), warehouse.address(),
                warehouse.selectionReason(), warehouse.serviceStatus(), warehouse.priority(), warehouse.preferred(), warehouse.selectedAt(),
                warehouse.latitude(), warehouse.longitude());
        RouteSnapshot route = maps.preview(new MapRoutingPort.MapRouteRequest(warehouseSnapshot, addressSnapshot));
        if (paymentOption == null) throw new SalesInvariantViolation("Payment option is required");
        PaymentOption option = paymentOption;
        boolean creditAuthorized = option != PaymentOption.CREDIT_LINE || commercialSnapshot.credit().canAuthorize(total);
        if (option == PaymentOption.CREDIT_LINE && !commercialSnapshot.paymentTerms().credit()) {
            throw new SalesInvariantViolation("Client payment terms do not allow credit line");
        }
        if (option == PaymentOption.CREDIT_LINE && !creditAuthorized) {
            throw new SalesInvariantViolation("Client credit is blocked, overdue or insufficient");
        }
        PaymentSnapshot payment = new PaymentSnapshot(option, commercialSnapshot.paymentTerms().code(), total,
                currency, creditAuthorized);
        DeliverySnapshot delivery = new DeliverySnapshot(requestedDate, deliveryNotes, addressSnapshot, warehouseSnapshot, route);
        return new BaseAssembly(delivery, commercialSnapshot, payment);
    }

    private DeliveryAddressSnapshot resolveAddress(CurrentAccessContext context, String accountId, String addressId,
                                                  Address manualAddress) {
        ClientAccountAddress address;
        if (manualAddress != null) {
            validateGeography(manualAddress);
            String id = addressId == null || addressId.isBlank() ? "MANUAL-ADDRESS" : addressId.trim();
            return new DeliveryAddressSnapshot(id, "Manual address", manualAddress, false);
        }
        if (addressId != null && !addressId.isBlank()) {
            address = context.hasRole(MembershipRole.BUYER)
                    ? addresses.findForBuyer(scope(context), workspace(context), context.membershipId().toString(), addressId)
                    .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account-address"))
                    : addresses.find(scope(context), workspace(context), accountId, addressId)
                    .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account-address"));
        } else if (context.hasRole(MembershipRole.BUYER)) {
            address = addresses.findDefaultForBuyer(scope(context), workspace(context), context.membershipId().toString())
                    .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account-address"));
        } else {
            throw new com.nexa.api.sales.domain.exception.SalesInvariantViolation("Delivery address is required");
        }
        validateGeography(address.address());
        return new DeliveryAddressSnapshot(address.id().toString(), address.label(), address.address(), address.defaultAddress());
    }

    private void validateGeography(Address address) {
        if (geography.resolve(address.departmentCode(), address.provinceCode(), address.districtCode()).isEmpty()) {
            throw new SalesInvariantViolation("Peru geography hierarchy is invalid");
        }
    }

    private List<PurchaseRequestLine> buyerLines(List<com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand.Line> requested,
                                                 CurrentAccessContext context) {
        List<PurchaseRequestLine> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand.Line> safeLines = requested == null ? List.of() : requested;
        Map<String, CatalogItemSnapshot> snapshots = catalog.findActiveById(safeLines.stream().map(line -> line == null ? null : line.catalogItemId()).toList(),
                context.tenantId().value(), context.workspaceId().value());
        for (var line : safeLines) {
            if (line == null || line.catalogItemId() == null || !seen.add(line.catalogItemId().trim())) {
                throw new SalesInvariantViolation("Buyer request line is duplicated or invalid");
            }
            CatalogItemSnapshot item = snapshots.get(line.catalogItemId().trim());
            if (item == null) throw new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("catalog-item");
            var quantity = new RequestedQuantity(line.quantity());
            String unit = line.unit() == null || line.unit().isBlank() ? "unit" : line.unit();
            result.add(new PurchaseRequestLine(new PurchaseRequestLineId(UUID.randomUUID()), item, quantity, unit, line.notes()));
        }
        if (result.isEmpty()) throw new SalesInvariantViolation("Buyer request requires a line");
        return List.copyOf(result);
    }

    private List<com.nexa.api.sales.domain.model.salesorder.SalesOrderLine> manualLines(
            List<com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand.Line> requested,
            String requestedCurrency, CurrentAccessContext context) {
        List<com.nexa.api.sales.domain.model.salesorder.SalesOrderLine> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String currency = normalizeCurrency(requestedCurrency);
        List<com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand.Line> safeLines = requested == null ? List.of() : requested;
        Map<UUID, com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort.Snapshot> skuSnapshots = sellableSkus == null
                ? Map.of()
                : sellableSkus.findActive(safeLines.stream().map(line -> line == null ? null : line.skuId()).toList(),
                        context.tenantId().value(), context.workspaceId().value());
        Map<String, CatalogItemSnapshot> catalogSnapshots = catalog.findActiveById(safeLines.stream()
                        .filter(line -> line != null && line.skuId() == null)
                        .map(com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand.Line::catalogItemId).toList(),
                context.tenantId().value(), context.workspaceId().value());
        for (var line : safeLines) {
            String identity = line == null ? null : line.skuId() != null ? "SKU:" + line.skuId() : line.catalogItemId();
            if (line == null || (line.skuId() == null && (line.catalogItemId() == null || line.catalogItemId().isBlank())) || !seen.add(identity.trim())) {
                throw new SalesInvariantViolation("Manual sales order line is duplicated or invalid");
            }
            String unit = line.unit() == null || line.unit().isBlank() ? "unit" : line.unit();
            BigDecimal quantity = new RequestedQuantity(line.quantity()).value();
            if (line.skuId() != null) {
                if (sellableSkus == null) throw new SalesInvariantViolation("Canonical SKU lookup is unavailable");
                var sku = skuSnapshots.get(line.skuId());
                if (sku == null) throw new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("sellable-sku");
                if (!currency.equals(sku.currency())) throw new SalesInvariantViolation("Order currency does not match catalog price");
                String legacyId = sku.legacyCatalogItemId() == null || sku.legacyCatalogItemId().isBlank() ? sku.skuCode() : sku.legacyCatalogItemId();
                result.add(new com.nexa.api.sales.domain.model.salesorder.SalesOrderLine(legacyId, sku.familyName(), sku.presentation(), quantity,
                        unit, sku.price(), sku.currency(), quantity.multiply(sku.price()), sku.skuId(), sku.familyId(), sku.skuCode(), sku.familyCode()));
            } else {
                CatalogItemSnapshot item = catalogSnapshots.get(line.catalogItemId().trim());
                if (item == null) throw new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("catalog-item");
                if (!currency.equals(item.price().currency())) throw new SalesInvariantViolation("Order currency does not match catalog price");
                result.add(new com.nexa.api.sales.domain.model.salesorder.SalesOrderLine(item.catalogItemId(), item.itemName(),
                        item.presentation(), quantity, unit, item.price().amount(), item.price().currency(), quantity.multiply(item.price().amount())));
            }
        }
        if (result.isEmpty()) throw new SalesInvariantViolation("Manual sales order requires a line");
        return List.copyOf(result);
    }

    private String resolveAccountIdForBuyer(CurrentAccessContext context, String requested) {
        String account = commercial.findForBuyer(scope(context), workspace(context), context.membershipId().toString())
                .map(ClientAccountCommercialPort.ClientAccountCommercialProfile::id)
                .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account"));
        if (requested != null && !requested.isBlank() && !account.equals(requested.trim())) {
            throw new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account");
        }
        return account;
    }

    private String resolveAccountIdForSales(CurrentAccessContext context, String requested) {
        if (requested == null || requested.isBlank()) throw new SalesInvariantViolation("Client account is required");
        return commercial.find(scope(context), workspace(context), requested.trim())
                .map(ClientAccountCommercialPort.ClientAccountCommercialProfile::id)
                .orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account"));
    }

    private static BigDecimal total(List<PurchaseRequestLine> lines) {
        return lines.stream().map(line -> line.quantity().value().multiply(line.catalogItem().price().amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String normalizeCurrency(String value) {
        if (value == null || value.isBlank() || !value.trim().matches("[A-Za-z]{3}")) {
            throw new SalesInvariantViolation("Currency is invalid");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }

    public record BuyerAssembly(List<PurchaseRequestLine> lines,
                                com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot snapshot) {
        public BuyerAssembly { lines = List.copyOf(lines); }
    }

    public record ManualAssembly(List<com.nexa.api.sales.domain.model.salesorder.SalesOrderLine> lines,
                                 com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority priority,
                                 com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderSnapshot snapshot) {
        public ManualAssembly { lines = List.copyOf(lines); }
    }

    private record BaseAssembly(DeliverySnapshot delivery,
                                CommercialSnapshot commercial, PaymentSnapshot payment) { }
}
