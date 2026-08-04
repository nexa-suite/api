package com.nexa.api.sales.infrastructure.salesorder;

import com.nexa.api.sales.application.exception.PurchaseRequestDraftConcurrencyException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderDraft;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderDraftStatus;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC adapter for the Sales-owned resumable manual-order draft. */
@Repository
@Profile("!test")
public class ManualSalesOrderDraftPersistenceAdapter implements ManualSalesOrderDraftPersistencePort {
    private static final String SCHEMA = "1.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ManualSalesOrderDraftPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView create(CurrentAccessContext context, String idempotencyKey) {
        UUID tenant = tenant(context);
        UUID workspace = workspace(context);
        UUID actor = actor(context);
        String key = idempotencyKey.trim();
        String hash = emptyDraftHash();
        Optional<IdempotencyClaim> existing = claim(tenant, workspace, actor, key, true);
        if (existing.isPresent()) return get(context, existing.get().draftId());

        UUID draftId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("insert into sales.manual_sales_order_draft "
                        + "(id,tenant_id,workspace_id,created_by_membership_id,status,version,created_at,updated_at) "
                        + "values (?,?,?,?,'DRAFT',0,?,?)",
                draftId, tenant, workspace, actor, timestamp, timestamp);
        int inserted = jdbc.update("insert into sales.manual_sales_order_draft_idempotency "
                        + "(tenant_id,workspace_id,actor_membership_id,idempotency_key,request_hash,draft_id,created_at) "
                        + "values (?,?,?,?,?,?,?) on conflict do nothing",
                tenant, workspace, actor, key, hash, draftId, timestamp);
        if (inserted == 0) {
            IdempotencyClaim winner = claim(tenant, workspace, actor, key, false)
                    .orElseThrow(() -> new IllegalStateException("Manual order draft idempotency claim disappeared"));
            return get(context, winner.draftId());
        }
        return get(context, draftId);
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView get(CurrentAccessContext context, UUID draftId) {
        return view(context, row(context, draftId, false));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView getForUpdate(CurrentAccessContext context, UUID draftId) {
        return view(context, row(context, draftId, true));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView updateClient(CurrentAccessContext context, UUID draftId,
                                                               long expectedVersion,
                                                               ManualSalesOrderDraftModels.ClientCommand command) {
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (command.clientAccountId() == null || command.requestedDeliveryDate() == null
                || command.requestedDeliveryDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Client and future delivery date are required");
        }
        PaymentOption payment = PaymentOption.from(command.paymentPreference());
        if (payment == null) throw new IllegalArgumentException("Payment preference is required");
        PurchaseRequestPriority priority = PurchaseRequestPriority.from(command.priority());
        ClientRow client = client(context, command.clientAccountId())
                .orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
        if (!"ACTIVE".equalsIgnoreCase(client.status())) throw new IllegalArgumentException("Client account is not active");
        String creditResult = creditResult(payment, client.availableCredit());
        String snapshot = json(clientSnapshot(client, creditResult));
        boolean clientComplete = !"UNAVAILABLE".equals(creditResult);
        boolean itemsComplete = allAvailableLines(context, draftId);
        String status = status(clientComplete, itemsComplete, false);
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbc.update("update sales.manual_sales_order_draft set client_account_id=?,priority=?,"
                        + "requested_delivery_date=?,payment_preference=?,currency=?,notes=?,credit_result=?,client_snapshot=?::jsonb,"
                        + "delivery_address_id=null,delivery_address_snapshot=null,route_snapshot=null,warehouse_id=null,"
                        + "warehouse_selection_snapshot=null,delivery_notes=null,status=?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and version=?",
                command.clientAccountId(), priority.name(), command.requestedDeliveryDate(), payment.name(), currency(command.currency()),
                command.notes(), creditResult, snapshot, status, timestamp, tenant(context), workspace(context), draftId, expectedVersion);
        if (updated != 1) throw new PurchaseRequestDraftConcurrencyException();
        return get(context, draftId);
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId,
                                                               long expectedVersion,
                                                               List<ManualSalesOrderDraftModels.LineCommand> commands) {
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (draft.clientAccountId() == null) throw new IllegalArgumentException("Client must be complete before items");
        if (commands == null || commands.isEmpty() || commands.size() > 100) {
            throw new IllegalArgumentException("At least one SKU line is required");
        }
        Set<UUID> resolvedIds = new HashSet<>();
        List<ResolvedLine> resolved = new ArrayList<>();
        for (ManualSalesOrderDraftModels.LineCommand command : commands) {
            if (command == null || command.quantity() == null || command.quantity().signum() <= 0
                    || (command.skuId() == null && blank(command.catalogItemId()))) {
                throw new IllegalArgumentException("Manual order SKU line is invalid");
            }
            SkuRow sku = findSku(context, command).orElseThrow(() -> new IllegalArgumentException("SKU is inactive or has no current price"));
            if (!resolvedIds.add(sku.id())) throw new IllegalArgumentException("Manual order cannot contain duplicate SKU lines");
            BigDecimal available = availability(context, sku.id());
            String availability = available.compareTo(command.quantity()) >= 0
                    ? "AVAILABLE" : available.signum() > 0 ? "LIMITED" : "UNAVAILABLE";
            resolved.add(new ResolvedLine(sku, command, availability));
        }

        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("delete from sales.manual_sales_order_draft_line where tenant_id=? and workspace_id=? and draft_id=?",
                tenant(context), workspace(context), draftId);
        for (ResolvedLine line : resolved) {
            SkuRow sku = line.sku();
            jdbc.update("insert into sales.manual_sales_order_draft_line "
                            + "(id,tenant_id,workspace_id,draft_id,sku_id,catalog_item_id,product_family_id,"
                            + "product_family_code_snapshot,product_family_name_snapshot,sku_code_snapshot,presentation_snapshot,"
                            + "unit_of_measure,quantity,base_unit_price,effective_unit_price,discount_amount,currency,"
                            + "availability_status,promotion_references,notes,created_at,updated_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'[]'::jsonb,?,?,?)",
                    UUID.randomUUID(), tenant(context), workspace(context), draftId, sku.id(), sku.catalogItemId(), sku.familyId(),
                    sku.familyCode(), sku.familyName(), sku.skuCode(), sku.presentation(),
                    unit(line.command().unit(), sku.unit()), line.command().quantity(), sku.price(), sku.price(), BigDecimal.ZERO,
                    sku.currency(), line.availability(), line.command().notes(), timestamp, timestamp);
        }
        boolean clientComplete = clientComplete(draft);
        String status = status(clientComplete, resolved.stream().allMatch(line -> "AVAILABLE".equals(line.availability())), false);
        int updated = jdbc.update("update sales.manual_sales_order_draft set delivery_address_id=null,delivery_address_snapshot=null,"
                        + "route_snapshot=null,warehouse_id=null,warehouse_selection_snapshot=null,delivery_notes=null,status=?,"
                        + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and version=?",
                status, timestamp, tenant(context), workspace(context), draftId, expectedVersion);
        if (updated != 1) throw new PurchaseRequestDraftConcurrencyException();
        return get(context, draftId);
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView updateDelivery(CurrentAccessContext context, UUID draftId,
                                                                 long expectedVersion,
                                                                 ManualSalesOrderDraftModels.DeliveryCommand command) {
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (draft.clientAccountId() == null || !hasLines(context, draftId)) {
            throw new IllegalArgumentException("Client and items must be complete before delivery");
        }
        AddressRow address = address(context, draft.clientAccountId(), command.addressId())
                .orElseThrow(() -> new IllegalArgumentException("Address is not active for client account"));
        if (address.latitude() == null || address.longitude() == null) {
            throw new IllegalArgumentException("Delivery address requires geocoded coordinates");
        }
        WarehouseRow warehouse = selectWarehouse(context, draftId);
        String provider = blank(command.routeProvider()) ? "LOCAL_ESTIMATE" : command.routeProvider().trim().toUpperCase(Locale.ROOT);
        double originLatitude = warehouse.latitude() == null ? -12.0464 : warehouse.latitude().doubleValue();
        double originLongitude = warehouse.longitude() == null ? -77.0428 : warehouse.longitude().doubleValue();
        double distance = Math.sqrt(Math.pow(address.latitude().doubleValue() - originLatitude, 2)
                + Math.pow(address.longitude().doubleValue() - originLongitude, 2)) * 111.0;
        long duration = Math.max(900, Math.round(distance / 24.0 * 3600));
        String addressSnapshot = json(addressSnapshot(address));
        String routeSnapshot = json(Map.of("schemaVersion", SCHEMA, "provider", provider, "estimated", true,
                "distanceKm", BigDecimal.valueOf(distance).setScale(3, RoundingMode.HALF_UP), "durationSeconds", duration,
                "originWarehouseId", warehouse.id().toString(), "destinationLatitude", address.latitude(),
                "destinationLongitude", address.longitude()));
        String warehouseSnapshot = json(Map.of("schemaVersion", SCHEMA, "strategy", "preferred-open-full-order-route-duration-distance-priority-id",
                "warehouseId", warehouse.id().toString(), "code", warehouse.code(), "name", warehouse.name(), "estimated", true));
        String status = clientComplete(draft) && allAvailableLines(context, draftId)
                ? ManualSalesOrderDraftStatus.READY_TO_CREATE.name() : ManualSalesOrderDraftStatus.DELIVERY_COMPLETE.name();
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbc.update("update sales.manual_sales_order_draft set delivery_address_id=?,delivery_address_snapshot=?::jsonb,"
                        + "route_snapshot=?::jsonb,warehouse_id=?,warehouse_selection_snapshot=?::jsonb,delivery_notes=?,status=?,"
                        + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and version=?",
                address.id(), addressSnapshot, routeSnapshot, warehouse.id(), warehouseSnapshot, command.deliveryNotes(), status,
                timestamp, tenant(context), workspace(context), draftId, expectedVersion);
        if (updated != 1) throw new PurchaseRequestDraftConcurrencyException();
        return get(context, draftId);
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView markCreated(CurrentAccessContext context, UUID draftId,
                                                              long expectedVersion, String salesOrderId) {
        UUID orderId;
        try { orderId = UUID.fromString(salesOrderId); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Sales order id is invalid", exception); }
        int updated = jdbc.update("update sales.manual_sales_order_draft set status='CREATED',sales_order_id=?,submitted_at=current_timestamp,"
                        + "version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?",
                orderId, tenant(context), workspace(context), draftId, expectedVersion);
        if (updated != 1) throw new PurchaseRequestDraftConcurrencyException();
        return get(context, draftId);
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView abandon(CurrentAccessContext context, UUID draftId, long expectedVersion) {
        mutable(context, draftId, expectedVersion);
        int updated = jdbc.update("update sales.manual_sales_order_draft set status='ABANDONED',version=version+1,updated_at=current_timestamp "
                        + "where tenant_id=? and workspace_id=? and id=? and version=?",
                tenant(context), workspace(context), draftId, expectedVersion);
        if (updated != 1) throw new PurchaseRequestDraftConcurrencyException();
        return get(context, draftId);
    }

    private DraftRow mutable(CurrentAccessContext context, UUID draftId, long expectedVersion) {
        DraftRow row = row(context, draftId, true);
        ManualSalesOrderDraft.requireMutable(ManualSalesOrderDraftStatus.valueOf(row.status()));
        if (row.version() != expectedVersion) throw new PurchaseRequestDraftConcurrencyException();
        return row;
    }

    private DraftRow row(CurrentAccessContext context, UUID draftId, boolean forUpdate) {
        if (draftId == null) throw new SalesResourceNotFoundException("sales-order");
        String sql = "select id,client_account_id,delivery_address_id,status,priority,requested_delivery_date,"
                + "payment_preference,currency,notes,delivery_notes,credit_result,client_snapshot::text,"
                + "delivery_address_snapshot::text,route_snapshot::text,warehouse_id,warehouse_selection_snapshot::text,"
                + "sales_order_id,version,created_at,updated_at,submitted_at from sales.manual_sales_order_draft "
                + "where tenant_id=? and workspace_id=? and id=?" + (forUpdate ? " for update" : "");
        return jdbc.query(sql, (rs, ignored) -> new DraftRow(rs.getObject("id", UUID.class),
                        nullableUuid(rs.getObject("client_account_id")), nullableUuid(rs.getObject("delivery_address_id")),
                        rs.getString("status"), rs.getString("priority"), rs.getObject("requested_delivery_date", LocalDate.class),
                        rs.getString("payment_preference"), rs.getString("currency"), rs.getString("notes"), rs.getString("delivery_notes"),
                        rs.getString("credit_result"), rs.getString("client_snapshot"), rs.getString("delivery_address_snapshot"),
                        rs.getString("route_snapshot"), nullableUuid(rs.getObject("warehouse_id")), rs.getString("warehouse_selection_snapshot"),
                        nullableUuid(rs.getObject("sales_order_id")), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant()),
                tenant(context), workspace(context), draftId)
                .stream().findFirst().orElseThrow(() -> new SalesResourceNotFoundException("sales-order"));
    }

    private ManualSalesOrderDraftModels.DraftView view(CurrentAccessContext context, DraftRow row) {
        ManualSalesOrderDraftModels.ClientView client = row.clientAccountId() == null ? null
                : client(context, row.clientAccountId()).map(value -> new ManualSalesOrderDraftModels.ClientView(
                value.id().toString(), value.code(), value.businessName(), value.commercialName(), value.taxIdentifierType(),
                value.taxIdentifierValue(), value.status(), value.paymentTerms(), value.creditLimit(), value.currentExposure(),
                value.availableCredit())).orElse(null);
        List<ManualSalesOrderDraftModels.LineView> lines = jdbc.query("select id,sku_id,catalog_item_id,product_family_name_snapshot,"
                        + "product_family_code_snapshot,sku_code_snapshot,presentation_snapshot,unit_of_measure,quantity,base_unit_price,"
                        + "effective_unit_price,discount_amount,currency,availability_status,notes from sales.manual_sales_order_draft_line "
                        + "where tenant_id=? and workspace_id=? and draft_id=? order by created_at,id",
                (rs, ignored) -> new ManualSalesOrderDraftModels.LineView(rs.getObject("id", UUID.class).toString(),
                        rs.getObject("sku_id", UUID.class).toString(), rs.getString("catalog_item_id"), rs.getString("product_family_name_snapshot"),
                        rs.getString("product_family_code_snapshot"), rs.getString("sku_code_snapshot"), rs.getString("presentation_snapshot"),
                        rs.getString("unit_of_measure"), rs.getBigDecimal("quantity"), rs.getBigDecimal("base_unit_price"),
                        rs.getBigDecimal("effective_unit_price"), rs.getBigDecimal("discount_amount"), rs.getString("currency"),
                        rs.getString("availability_status"), rs.getString("notes")), tenant(context), workspace(context), row.id());
        ManualSalesOrderDraftModels.DeliveryView delivery = row.deliveryAddressId() == null && row.deliveryAddressSnapshot() == null
                ? null : new ManualSalesOrderDraftModels.DeliveryView(row.deliveryAddressId() == null ? null : row.deliveryAddressId().toString(),
                row.deliveryAddressSnapshot(), row.routeSnapshot(), row.warehouseSelectionSnapshot(),
                row.warehouseId() == null ? null : row.warehouseId().toString(), routeProvider(row.routeSnapshot()), row.deliveryNotes());
        return new ManualSalesOrderDraftModels.DraftView(row.id().toString(), row.status(), row.version(), client,
                row.requestedDeliveryDate(), row.priority(), row.paymentPreference(), row.currency(), row.notes(), row.creditResult(),
                lines, delivery, ManualSalesOrderDraftStatus.READY_TO_CREATE.name().equals(row.status()),
                row.salesOrderId() == null ? null : row.salesOrderId().toString(), row.createdAt(), row.updatedAt(), row.submittedAt());
    }

    private Optional<ClientRow> client(CurrentAccessContext context, UUID id) {
        return jdbc.query("select id,code,business_name,commercial_name,tax_identifier_type,tax_identifier_value,status,"
                        + "payment_condition,coalesce(credit_limit,0),coalesce(current_commercial_exposure,0),coalesce(available_credit,0) "
                        + "from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                (rs, ignored) -> new ClientRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("business_name"),
                        rs.getString("commercial_name"), rs.getString("tax_identifier_type"), rs.getString("tax_identifier_value"),
                        rs.getString("status"), rs.getString("payment_condition"), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11)),
                tenant(context), workspace(context), id).stream().findFirst();
    }

    private Optional<SkuRow> findSku(CurrentAccessContext context, ManualSalesOrderDraftModels.LineCommand command) {
        String selector;
        Object selectorValue;
        if (command.skuId() != null) {
            selector = "s.id=?";
            selectorValue = command.skuId();
        } else {
            selector = "s.legacy_catalog_item_id=?";
            selectorValue = command.catalogItemId().trim();
        }
        return jdbc.query("select s.id,coalesce(nullif(s.legacy_catalog_item_id,''),s.sku_code),s.family_id,f.family_code,f.name,"
                        + "s.sku_code,s.presentation,s.unit_of_measure,p.amount,p.currency from catalog_management.sellable_sku s "
                        + "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                        + "join lateral (select amount,currency from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and "
                        + "p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from <= current_timestamp "
                        + "and (p0.valid_until is null or p0.valid_until > current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true "
                        + "where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible and " + selector,
                (rs, ignored) -> new SkuRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10)),
                tenant(context), workspace(context), selectorValue).stream().findFirst();
    }

    private Optional<AddressRow> address(CurrentAccessContext context, UUID clientId, UUID addressId) {
        return jdbc.query("select id,recipient_name,recipient_phone,road_type,street_name,street_number,interior,department_code,"
                        + "province_code,district_code,postal_code,reference,receiving_instructions,receiving_hours,latitude,longitude,place_id,source "
                        + "from sales.client_account_address where tenant_id=? and workspace_id=? and client_account_id=? and id=? and status='ACTIVE'",
                (rs, ignored) -> new AddressRow(rs.getObject("id", UUID.class), rs.getString("recipient_name"), rs.getString("recipient_phone"),
                        rs.getString("road_type"), rs.getString("street_name"), rs.getString("street_number"), rs.getString("interior"),
                        rs.getString("department_code"), rs.getString("province_code"), rs.getString("district_code"), rs.getString("postal_code"),
                        rs.getString("reference"), rs.getString("receiving_instructions"), rs.getString("receiving_hours"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("place_id"), rs.getString("source")),
                tenant(context), workspace(context), clientId, addressId).stream().findFirst();
    }

    private WarehouseRow selectWarehouse(CurrentAccessContext context, UUID draftId) {
        Map<UUID, BigDecimal> requested = new HashMap<>();
        jdbc.query("select sku_id,quantity from sales.manual_sales_order_draft_line where tenant_id=? and workspace_id=? and draft_id=?",
                (rs, ignored) -> requested.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenant(context), workspace(context), draftId);
        if (requested.isEmpty()) throw new IllegalArgumentException("Products must be complete before warehouse selection");
        List<WarehouseRow> candidates = jdbc.query("select w.id,w.code,w.name,c.latitude,c.longitude from warehouse.warehouse w "
                        + "left join warehouse.warehouse_service_configuration c on c.warehouse_id=w.id and c.tenant_id=w.tenant_id and c.workspace_id=w.workspace_id "
                        + "where w.tenant_id=? and w.workspace_id=? and w.status='ACTIVE' and coalesce(c.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "order by coalesce(c.preferred,false) desc,coalesce(c.priority,0) desc,w.id",
                (rs, ignored) -> new WarehouseRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5)),
                tenant(context), workspace(context));
        for (WarehouseRow warehouse : candidates) {
            Map<UUID, BigDecimal> available = new HashMap<>();
            jdbc.query("select sku_id,coalesce(sum(stock_quantity-reserved_quantity),0) from warehouse.inventory_lot where tenant_id=? and workspace_id=? "
                            + "and warehouse_id=? and status='AVAILABLE' and expiration_date >= current_date and sku_id is not null group by sku_id",
                    (rs, ignored) -> available.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenant(context), workspace(context), warehouse.id());
            if (requested.entrySet().stream().allMatch(entry -> available.getOrDefault(entry.getKey(), BigDecimal.ZERO).compareTo(entry.getValue()) >= 0)) {
                return warehouse;
            }
        }
        throw new IllegalArgumentException("Warehouse serviceability conflict: no warehouse can fulfill the manual order");
    }

    private BigDecimal availability(CurrentAccessContext context, UUID skuId) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(stock_quantity-reserved_quantity),0) from warehouse.inventory_lot "
                        + "where tenant_id=? and workspace_id=? and sku_id=? and status='AVAILABLE' and expiration_date >= current_date",
                BigDecimal.class, tenant(context), workspace(context), skuId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean allAvailableLines(CurrentAccessContext context, UUID draftId) {
        Boolean exists = jdbc.queryForObject("select exists(select 1 from sales.manual_sales_order_draft_line where tenant_id=? and workspace_id=? and draft_id=?)", Boolean.class,
                tenant(context), workspace(context), draftId);
        if (!Boolean.TRUE.equals(exists)) return false;
        Boolean unavailable = jdbc.queryForObject("select exists(select 1 from sales.manual_sales_order_draft_line where tenant_id=? and workspace_id=? and draft_id=? and availability_status <> 'AVAILABLE')", Boolean.class,
                tenant(context), workspace(context), draftId);
        return !Boolean.TRUE.equals(unavailable);
    }

    private boolean hasLines(CurrentAccessContext context, UUID draftId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.manual_sales_order_draft_line where tenant_id=? and workspace_id=? and draft_id=?)", Boolean.class,
                tenant(context), workspace(context), draftId));
    }

    private static boolean clientComplete(DraftRow draft) {
        return draft.clientAccountId() != null && draft.requestedDeliveryDate() != null && draft.paymentPreference() != null
                && !"UNAVAILABLE".equalsIgnoreCase(draft.creditResult());
    }

    private static String status(boolean client, boolean items, boolean delivery) {
        return ManualSalesOrderDraft.status(client, items, delivery).name();
    }

    private static String creditResult(PaymentOption payment, BigDecimal availableCredit) {
        if (payment != PaymentOption.CREDIT_LINE) return "NOT_APPLICABLE";
        return availableCredit != null && availableCredit.signum() >= 0 ? "AVAILABLE" : "UNAVAILABLE";
    }

    private static String currency(String value) {
        String normalized = blank(value) ? "PEN" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency is invalid");
        return normalized;
    }

    private static String unit(String requested, String fallback) { return blank(requested) ? fallback : requested.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static UUID nullableUuid(Object value) { return value == null ? null : (value instanceof UUID uuid ? uuid : UUID.fromString(value.toString())); }
    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }
    private static String routeProvider(String routeSnapshot) { return routeSnapshot == null ? null : "LOCAL_ESTIMATE"; }

    private Optional<IdempotencyClaim> claim(UUID tenant, UUID workspace, UUID actor, String key, boolean lock) {
        return jdbc.query("select request_hash,draft_id from sales.manual_sales_order_draft_idempotency where tenant_id=? and workspace_id=? "
                        + "and actor_membership_id=? and idempotency_key=?" + (lock ? " for update" : ""),
                (rs, ignored) -> new IdempotencyClaim(rs.getString(1), rs.getObject(2, UUID.class)), tenant, workspace, actor, key)
                .stream().findFirst();
    }

    private static String emptyDraftHash() {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("MANUAL_SALES_ORDER_DRAFT_V1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private Map<String, Object> clientSnapshot(ClientRow client, String creditResult) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA); value.put("clientAccountId", client.id()); value.put("code", client.code());
        value.put("businessName", client.businessName()); value.put("commercialName", client.commercialName());
        value.put("taxIdentifierType", client.taxIdentifierType()); value.put("taxIdentifierValue", client.taxIdentifierValue());
        value.put("status", client.status()); value.put("paymentTerms", client.paymentTerms()); value.put("creditLimit", client.creditLimit());
        value.put("currentExposure", client.currentExposure()); value.put("availableCredit", client.availableCredit()); value.put("creditResult", creditResult);
        return value;
    }

    private Map<String, Object> addressSnapshot(AddressRow address) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA); value.put("addressId", address.id()); value.put("recipient", address.recipient());
        value.put("phone", nullSafe(address.phone())); value.put("roadType", nullSafe(address.roadType())); value.put("street", nullSafe(address.street()));
        value.put("number", nullSafe(address.number())); value.put("interior", nullSafe(address.interior())); value.put("department", nullSafe(address.department()));
        value.put("province", nullSafe(address.province())); value.put("district", nullSafe(address.district())); value.put("postalCode", nullSafe(address.postalCode()));
        value.put("reference", nullSafe(address.reference())); value.put("receivingInstructions", nullSafe(address.instructions()));
        value.put("receivingHours", nullSafe(address.hours())); value.put("latitude", address.latitude()); value.put("longitude", address.longitude());
        value.put("placeId", nullSafe(address.placeId())); value.put("source", nullSafe(address.source()));
        return value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Snapshot serialization failed", exception); }
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private record DraftRow(UUID id, UUID clientAccountId, UUID deliveryAddressId, String status, String priority,
                             LocalDate requestedDeliveryDate, String paymentPreference, String currency, String notes,
                             String deliveryNotes, String creditResult, String clientSnapshot, String deliveryAddressSnapshot,
                             String routeSnapshot, UUID warehouseId, String warehouseSelectionSnapshot, UUID salesOrderId,
                             long version, Instant createdAt, Instant updatedAt, Instant submittedAt) { }
    private record IdempotencyClaim(String requestHash, UUID draftId) { }
    private record ClientRow(UUID id, String code, String businessName, String commercialName, String taxIdentifierType,
                             String taxIdentifierValue, String status, String paymentTerms, BigDecimal creditLimit,
                             BigDecimal currentExposure, BigDecimal availableCredit) { }
    private record SkuRow(UUID id, String catalogItemId, UUID familyId, String familyCode, String familyName, String skuCode,
                          String presentation, String unit, BigDecimal price, String currency) { }
    private record ResolvedLine(SkuRow sku, ManualSalesOrderDraftModels.LineCommand command, String availability) { }
    private record AddressRow(UUID id, String recipient, String phone, String roadType, String street, String number,
                              String interior, String department, String province, String district, String postalCode,
                              String reference, String instructions, String hours, BigDecimal latitude, BigDecimal longitude,
                              String placeId, String source) { }
    private record WarehouseRow(UUID id, String code, String name, BigDecimal latitude, BigDecimal longitude) { }
}
