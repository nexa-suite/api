package com.nexa.api.sales.infrastructure.purchaserequestdraft;

import com.nexa.api.sales.application.port.PurchaseRequestDraftPort;
import com.nexa.api.sales.application.exception.PurchaseRequestDraftConcurrencyException;
import com.nexa.api.sales.application.purchaserequestdraft.model.PurchaseRequestDraftModels;
import com.nexa.api.sales.domain.model.purchaserequestdraft.PurchaseRequestDraft;
import com.nexa.api.sales.domain.model.purchaserequestdraft.PurchaseRequestDraftStatus;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Persistence adapter for draft readiness, pricing, destination and warehouse snapshots. */
@Profile("!test")
@Repository
public class PurchaseRequestDraftService implements PurchaseRequestDraftPort {
    private static final String SCHEMA = "1.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PurchaseRequestDraftService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView create(CurrentAccessContext context, UUID clientAccountId, LocalDate requestedDeliveryDate) {
        buyerWrite(context);
        if (clientAccountId == null || requestedDeliveryDate == null || requestedDeliveryDate.isBefore(LocalDate.now())) throw new IllegalArgumentException("Client account and future delivery date are required");
        requireBuyerClient(context, clientAccountId);
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sales.purchase_request_draft (id,tenant_id,workspace_id,buyer_membership_id,client_account_id,status,requested_delivery_date,version,snapshot_schema_version,created_at,updated_at) values (?,?,?,?,?,'DRAFT',?,0,?,?,?)",
                id, tenant(context), workspace(context), context.membershipId().value(), clientAccountId, requestedDeliveryDate, SCHEMA, Timestamp.from(now), Timestamp.from(now));
        return get(context, id);
    }

    @Transactional(readOnly = true)
    public PurchaseRequestDraftModels.DraftView get(CurrentAccessContext context, UUID draftId) {
        buyerRead(context);
        DraftRow row = jdbc.query("select id,client_account_id,buyer_membership_id,status,version,requested_delivery_date,payment_preference,credit_result,route_provider,created_at,updated_at,submitted_at from sales.purchase_request_draft where tenant_id=? and workspace_id=? and id=? and buyer_membership_id=?",
                (rs, n) -> new DraftRow(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class), rs.getObject("buyer_membership_id", UUID.class), rs.getString("status"), rs.getLong("version"), rs.getObject("requested_delivery_date", LocalDate.class), rs.getString("payment_preference"), rs.getString("credit_result"), rs.getString("route_provider"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant()), tenant(context), workspace(context), draftId, context.membershipId().value()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Purchase request draft not found"));
        List<PurchaseRequestDraftModels.LineView> lines = jdbc.query("select id,sku_id,sku_code_snapshot,presentation_snapshot,quantity,unit,base_unit_price,effective_unit_price,discount_amount,currency,notes from sales.purchase_request_draft_line where tenant_id=? and workspace_id=? and draft_id=? order by created_at,id",
                (rs, n) -> new PurchaseRequestDraftModels.LineView(rs.getObject("id", UUID.class).toString(), rs.getObject("sku_id", UUID.class).toString(), rs.getString("sku_code_snapshot"), rs.getString("presentation_snapshot"), rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getBigDecimal("base_unit_price"), rs.getBigDecimal("effective_unit_price"), rs.getBigDecimal("discount_amount"), rs.getString("currency"), rs.getString("notes")), tenant(context), workspace(context), draftId);
        PurchaseRequestDraftModels.DestinationView destination = jdbc.query("select address_id,address_snapshot::text,snapshot_schema_version from sales.purchase_request_draft_destination where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> new PurchaseRequestDraftModels.DestinationView(rs.getObject("address_id", UUID.class).toString(), rs.getString("address_snapshot"), rs.getString("snapshot_schema_version")), tenant(context), workspace(context), draftId).stream().findFirst().orElse(null);
        PurchaseRequestDraftModels.RouteView route = jdbc.query("select provider,estimated,route_snapshot::text,snapshot_schema_version,calculated_at from sales.purchase_request_draft_route where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> new PurchaseRequestDraftModels.RouteView(rs.getString("provider"), rs.getBoolean("estimated"), rs.getString("route_snapshot"), rs.getString("snapshot_schema_version"), rs.getTimestamp("calculated_at").toInstant()), tenant(context), workspace(context), draftId).stream().findFirst().orElse(null);
        PurchaseRequestDraftModels.WarehouseSelectionView selection = jdbc.query("select warehouse_id,selection_snapshot::text,snapshot_schema_version,selected_at from sales.purchase_request_draft_warehouse_selection where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> new PurchaseRequestDraftModels.WarehouseSelectionView(rs.getObject("warehouse_id", UUID.class).toString(), rs.getString("selection_snapshot"), rs.getString("snapshot_schema_version"), rs.getTimestamp("selected_at").toInstant()), tenant(context), workspace(context), draftId).stream().findFirst().orElse(null);
        return new PurchaseRequestDraftModels.DraftView(row.id.toString(), row.clientAccountId.toString(), row.buyerMembershipId.toString(), row.status, row.version, row.requestedDeliveryDate, row.paymentPreference, row.creditResult, row.routeProvider, lines, destination, route, selection, row.createdAt, row.updatedAt, row.submittedAt);
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId, long expectedVersion, List<PurchaseRequestDraftPort.LineCommand> commands) {
        buyerWrite(context);
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (commands == null || commands.isEmpty() || commands.size() > 100) throw new IllegalArgumentException("At least one SKU line is required");
        if (commands.stream().anyMatch(command -> command == null || command.skuId() == null || command.quantity() == null || command.quantity().signum() <= 0)) throw new IllegalArgumentException("Draft SKU line is invalid");
        Set<UUID> skuIds = new HashSet<>();
        commands.forEach(command -> { if (!skuIds.add(command.skuId())) throw new IllegalArgumentException("Draft cannot contain duplicate SKU lines"); });
        String placeholders = skuIds.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> priceArguments = new ArrayList<>();
        priceArguments.add(tenant(context)); priceArguments.add(workspace(context)); priceArguments.addAll(skuIds);
        Map<UUID, PriceRow> prices = new HashMap<>();
        jdbc.query("select s.id,s.family_id,f.family_code,s.sku_code,s.presentation,coalesce(p.amount,0),coalesce(p.currency,'PEN') from catalog_management.sellable_sku s join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id left join lateral (select amount,currency from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from <= current_timestamp and (p0.valid_until is null or p0.valid_until > current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true where s.tenant_id=? and s.workspace_id=? and s.id in (" + placeholders + ") and s.status='ACTIVE' and s.visible",
                (rs, n) -> new PriceRow(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class), rs.getString("family_code"), rs.getString("sku_code"), rs.getString("presentation"), rs.getBigDecimal(6), rs.getString(7)), priceArguments.toArray()).forEach(row -> prices.put(row.skuId(), row));
        if (prices.size() != skuIds.size()) throw new IllegalArgumentException("SKU is not active or has no serviceable price");
        jdbc.update("delete from sales.purchase_request_draft_line where tenant_id=? and workspace_id=? and draft_id=?", tenant(context), workspace(context), draftId);
        Instant now = Instant.now();
        String insertLineSql = "insert into sales.purchase_request_draft_line (id,tenant_id,workspace_id,draft_id,sku_id,sku_code_snapshot,presentation_snapshot,quantity,unit,base_unit_price,effective_unit_price,discount_amount,currency,promotion_references,notes,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,'[]'::jsonb,?,?,?)";
        jdbc.batchUpdate(insertLineSql, commands, commands.size(), (ps, command) -> {
            PriceRow price = prices.get(command.skuId());
            ps.setObject(1, UUID.randomUUID()); ps.setObject(2, tenant(context)); ps.setObject(3, workspace(context)); ps.setObject(4, draftId);
            ps.setObject(5, command.skuId()); ps.setString(6, price.skuCode()); ps.setString(7, price.presentation()); ps.setBigDecimal(8, command.quantity());
            ps.setString(9, command.unit() == null || command.unit().isBlank() ? "UNIT" : command.unit()); ps.setBigDecimal(10, price.amount()); ps.setBigDecimal(11, price.amount()); ps.setBigDecimal(12, BigDecimal.ZERO); ps.setString(13, price.currency()); ps.setString(14, command.notes()); ps.setTimestamp(15, Timestamp.from(now)); ps.setTimestamp(16, Timestamp.from(now));
        });
        updateStatus(context, draftId, draft.version, true, hasDestination(draftId, context), hasRoute(draftId, context), hasCommercial(draft));
        return get(context, draftId);
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView setDestination(CurrentAccessContext context, UUID draftId, long expectedVersion, UUID addressId) {
        buyerWrite(context);
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (addressId == null) throw new IllegalArgumentException("Address id is required");
        AddressRow address = jdbc.query("select id,recipient_name,recipient_phone,road_type,street_name,street_number,interior,department_code,province_code,district_code,postal_code,reference,receiving_instructions,receiving_hours,latitude,longitude,place_id,source from sales.client_account_address where tenant_id=? and workspace_id=? and client_account_id=? and id=? and status='ACTIVE'", (rs, n) -> new AddressRow(rs), tenant(context), workspace(context), draft.clientAccountId, addressId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Address is not active for client account"));
        Map<String, Object> addressMap = new LinkedHashMap<>();
        addressMap.put("schemaVersion", SCHEMA); addressMap.put("addressId", address.id.toString()); addressMap.put("recipient", address.recipient);
        addressMap.put("phone", nullSafe(address.phone)); addressMap.put("roadType", nullSafe(address.roadType)); addressMap.put("street", nullSafe(address.street)); addressMap.put("number", nullSafe(address.number)); addressMap.put("interior", nullSafe(address.interior));
        addressMap.put("department", nullSafe(address.department)); addressMap.put("province", nullSafe(address.province)); addressMap.put("district", nullSafe(address.district)); addressMap.put("postalCode", nullSafe(address.postalCode)); addressMap.put("reference", nullSafe(address.reference));
        addressMap.put("receivingInstructions", nullSafe(address.instructions)); addressMap.put("receivingHours", nullSafe(address.hours)); addressMap.put("latitude", address.latitude == null ? "" : address.latitude); addressMap.put("longitude", address.longitude == null ? "" : address.longitude); addressMap.put("placeId", nullSafe(address.placeId)); addressMap.put("source", nullSafe(address.source));
        String snapshot = json(addressMap);
        Instant now = Instant.now();
        jdbc.update("insert into sales.purchase_request_draft_destination (draft_id,tenant_id,workspace_id,address_id,address_snapshot,snapshot_schema_version,updated_at) values (?,?,?,?,?::jsonb,?,?) on conflict (draft_id) do update set address_id=excluded.address_id,address_snapshot=excluded.address_snapshot,snapshot_schema_version=excluded.snapshot_schema_version,updated_at=excluded.updated_at", draftId, tenant(context), workspace(context), addressId, snapshot, SCHEMA, Timestamp.from(now));
        updateStatus(context, draftId, draft.version, hasLines(draftId, context), true, false, false);
        return get(context, draftId);
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView previewRoute(CurrentAccessContext context, UUID draftId, long expectedVersion, String provider) {
        buyerWrite(context);
        DraftRow draft = mutable(context, draftId, expectedVersion);
        AddressCoordinates destination = jdbc.query("select a.latitude,a.longitude from sales.purchase_request_draft_destination d join sales.client_account_address a on a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.id=d.address_id where d.tenant_id=? and d.workspace_id=? and d.draft_id=?", (rs, n) -> new AddressCoordinates(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenant(context), workspace(context), draftId).stream().findFirst().orElseThrow(() -> new IllegalStateException("Destination must be complete before route preview"));
        if (destination.latitude == null || destination.longitude == null) throw new IllegalStateException("Destination requires geocoded coordinates");
        WarehouseRow warehouse = selectWarehouse(context, draftId);
        double distance = Math.sqrt(Math.pow(destination.latitude.doubleValue() + 12.0464, 2) + Math.pow(destination.longitude.doubleValue() + 77.0428, 2)) * 111.0;
        long duration = Math.max(900, Math.round(distance / 24.0 * 3600));
        String routeProvider = provider == null || provider.isBlank() ? "LOCAL_ESTIMATE" : provider.trim().toUpperCase(java.util.Locale.ROOT);
        String route = json(Map.of("schemaVersion", SCHEMA, "provider", routeProvider, "estimated", true, "distanceKm", BigDecimal.valueOf(distance).setScale(3, java.math.RoundingMode.HALF_UP), "durationSeconds", duration, "originWarehouseId", warehouse.id.toString(), "destinationLatitude", destination.latitude, "destinationLongitude", destination.longitude));
        String selection = json(Map.of("schemaVersion", SCHEMA, "strategy", "preferred-open-full-order-route-duration-distance-priority-id", "warehouseId", warehouse.id.toString(), "estimated", true));
        Instant now = Instant.now();
        jdbc.update("insert into sales.purchase_request_draft_route (draft_id,tenant_id,workspace_id,provider,estimated,route_snapshot,snapshot_schema_version,calculated_at) values (?,?,?,?,?,?::jsonb,?,?) on conflict (draft_id) do update set provider=excluded.provider,estimated=excluded.estimated,route_snapshot=excluded.route_snapshot,snapshot_schema_version=excluded.snapshot_schema_version,calculated_at=excluded.calculated_at", draftId, tenant(context), workspace(context), routeProvider, true, route, SCHEMA, Timestamp.from(now));
        jdbc.update("insert into sales.purchase_request_draft_warehouse_selection (draft_id,tenant_id,workspace_id,warehouse_id,selection_snapshot,snapshot_schema_version,selected_at) values (?,?,?,?,?::jsonb,?,?) on conflict (draft_id) do update set warehouse_id=excluded.warehouse_id,selection_snapshot=excluded.selection_snapshot,snapshot_schema_version=excluded.snapshot_schema_version,selected_at=excluded.selected_at", draftId, tenant(context), workspace(context), warehouse.id, selection, SCHEMA, Timestamp.from(now));
        updateStatus(context, draftId, draft.version, hasLines(draftId, context), true, true, hasCommercial(draft));
        return get(context, draftId);
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView setPreferences(CurrentAccessContext context, UUID draftId, long expectedVersion, String paymentPreference, LocalDate requestedDeliveryDate) {
        buyerWrite(context);
        DraftRow draft = mutable(context, draftId, expectedVersion);
        if (paymentPreference == null || !Set.of("CREDIT_LINE", "BANK_TRANSFER", "CARD_STRIPE", "CASH", "CASH_ON_DELIVERY").contains(paymentPreference.trim().toUpperCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Payment preference is invalid");
        if (requestedDeliveryDate == null || requestedDeliveryDate.isBefore(LocalDate.now())) throw new IllegalArgumentException("Requested delivery date is invalid");
        jdbc.update("update sales.purchase_request_draft set payment_preference=?,requested_delivery_date=?,credit_result=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?", paymentPreference.trim().toUpperCase(java.util.Locale.ROOT), requestedDeliveryDate, creditResult(context, draft.clientAccountId, paymentPreference), tenant(context), workspace(context), draftId, expectedVersion);
        updateStatus(context, draftId, expectedVersion + 1, hasLines(draftId, context), hasDestination(draftId, context), hasRoute(draftId, context), true);
        return get(context, draftId);
    }

    @Transactional(readOnly = true)
    public PurchaseRequestDraftModels.ReviewView review(CurrentAccessContext context, UUID draftId) {
        PurchaseRequestDraftModels.DraftView draft = get(context, draftId);
        List<String> missing = new ArrayList<>();
        if (draft.lines().isEmpty()) missing.add("products");
        if (draft.destination() == null) missing.add("destination");
        if (draft.route() == null || draft.warehouseSelection() == null) missing.add("route");
        if (draft.paymentPreference() == null || draft.requestedDeliveryDate() == null) missing.add("commercial");
        return new PurchaseRequestDraftModels.ReviewView(draft, !draft.lines().isEmpty(), draft.destination() != null, draft.route() != null && draft.warehouseSelection() != null, draft.paymentPreference() != null && draft.requestedDeliveryDate() != null, missing.isEmpty() && draft.status().equals(PurchaseRequestDraftStatus.READY_TO_SUBMIT.name()), missing);
    }

    @Transactional
    public PurchaseRequestDraftModels.DraftView submit(CurrentAccessContext context, UUID draftId, long expectedVersion, String idempotencyKey) {
        buyerWrite(context);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) throw new IllegalArgumentException("Idempotency-Key is required");
        String requestHash = requestHash(draftId, expectedVersion);
        IdempotencyClaim existingClaim = jdbc.query("select request_hash,draft_id from sales.purchase_request_draft_idempotency where tenant_id=? and workspace_id=? and buyer_membership_id=? and idempotency_key=?", (rs, n) -> new IdempotencyClaim(rs.getString(1), rs.getObject(2, UUID.class)), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
        if (existingClaim != null) {
            if (!requestHash.equalsIgnoreCase(existingClaim.requestHash())) throw new IllegalStateException("Idempotency-Key payload conflict");
            return get(context, existingClaim.draftId());
        }
        DraftRow draft = mutable(context, draftId, expectedVersion);
        PurchaseRequestDraftModels.ReviewView review = review(context, draftId);
        if (!review.readyToSubmit()) throw new IllegalStateException("Purchase request draft is not ready to submit");
        int idempotencyClaimed = jdbc.update("insert into sales.purchase_request_draft_idempotency (tenant_id,workspace_id,buyer_membership_id,idempotency_key,request_hash,draft_id,created_at) values (?,?,?,?,?,?,current_timestamp) on conflict do nothing", tenant(context), workspace(context), context.membershipId().value(), idempotencyKey, requestHash, draftId);
        if (idempotencyClaimed == 0) {
            var claim = jdbc.query("select request_hash,draft_id from sales.purchase_request_draft_idempotency where tenant_id=? and workspace_id=? and buyer_membership_id=? and idempotency_key=?", (rs, n) -> new IdempotencyClaim(rs.getString(1), rs.getObject(2, UUID.class)), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElseThrow();
            if (!requestHash.equalsIgnoreCase(claim.requestHash())) throw new IllegalStateException("Idempotency-Key payload conflict");
            UUID claimedDraft = claim.draftId();
            if (!draftId.equals(claimedDraft)) return get(context, claimedDraft);
        }
        submitPurchaseRequest(context, draft);
        if (jdbc.update("update sales.purchase_request_draft set status='SUBMITTED',submitted_at=current_timestamp,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?", tenant(context), workspace(context), draftId, expectedVersion) != 1) {
            throw new PurchaseRequestDraftConcurrencyException();
        }
        return get(context, draftId);
    }

    private DraftRow mutable(CurrentAccessContext context, UUID id, long version) {
        DraftRow row = jdbc.query("select id,client_account_id,buyer_membership_id,status,version,requested_delivery_date,payment_preference,credit_result,route_provider,created_at,updated_at,submitted_at from sales.purchase_request_draft where tenant_id=? and workspace_id=? and id=? and buyer_membership_id=? for update", (rs, n) -> new DraftRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant(), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()), tenant(context), workspace(context), id, context.membershipId().value()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Purchase request draft not found"));
        PurchaseRequestDraft.requireMutable(PurchaseRequestDraftStatus.valueOf(row.status));
        if (row.version != version) throw new PurchaseRequestDraftConcurrencyException();
        return row;
    }

    private void updateStatus(CurrentAccessContext context, UUID id, long version, boolean products, boolean destination, boolean route, boolean commercial) {
        PurchaseRequestDraftStatus status = PurchaseRequestDraft.status(products, destination, route, commercial);
        if (jdbc.update("update sales.purchase_request_draft set status=?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and version=?", status.name(), tenant(context), workspace(context), id, version) != 1) {
            throw new PurchaseRequestDraftConcurrencyException();
        }
    }
    private boolean hasLines(UUID id, CurrentAccessContext c) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.purchase_request_draft_line where tenant_id=? and workspace_id=? and draft_id=?)", Boolean.class, tenant(c), workspace(c), id)); }
    private boolean hasDestination(UUID id, CurrentAccessContext c) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.purchase_request_draft_destination where tenant_id=? and workspace_id=? and draft_id=?)", Boolean.class, tenant(c), workspace(c), id)); }
    private boolean hasRoute(UUID id, CurrentAccessContext c) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.purchase_request_draft_route where tenant_id=? and workspace_id=? and draft_id=?)", Boolean.class, tenant(c), workspace(c), id)); }
    private boolean hasCommercial(DraftRow d) { return d.paymentPreference != null && d.requestedDeliveryDate != null; }
    private String creditResult(CurrentAccessContext context, UUID clientId, String payment) {
        if (!"CREDIT_LINE".equalsIgnoreCase(payment)) return "NOT_APPLICABLE";
        BigDecimal limit = jdbc.queryForObject("select coalesce(credit_limit,0) from sales.client_account where tenant_id=? and workspace_id=? and id=?", BigDecimal.class, tenant(context), workspace(context), clientId);
        return limit != null && limit.signum() > 0 ? "AVAILABLE" : "UNAVAILABLE";
    }
    private WarehouseRow selectWarehouse(CurrentAccessContext context, UUID draftId) {
        Map<UUID, BigDecimal> requested = jdbc.query("select sku_id,quantity from sales.purchase_request_draft_line where tenant_id=? and workspace_id=? and draft_id=? order by sku_id", (rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenant(context), workspace(context), draftId).stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (requested.isEmpty()) throw new IllegalStateException("Products must be complete before warehouse selection");
        List<WarehouseRow> candidates = jdbc.query("select w.id,w.code,w.name from warehouse.warehouse w left join warehouse.warehouse_service_configuration c on c.warehouse_id=w.id and c.tenant_id=w.tenant_id and c.workspace_id=w.workspace_id where w.tenant_id=? and w.workspace_id=? and w.status='ACTIVE' and coalesce(c.service_status,'OPERATIONAL')='OPERATIONAL' order by coalesce(c.preferred,false) desc,coalesce(c.priority,0) desc,w.id", (rs, n) -> new WarehouseRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), tenant(context), workspace(context));
        for (WarehouseRow candidate : candidates) {
            Map<UUID, BigDecimal> availability = jdbc.query("select sku_id,coalesce(sum(stock_quantity-reserved_quantity),0) from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=? and status='AVAILABLE' and expiration_date >= current_date and sku_id is not null group by sku_id", (rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenant(context), workspace(context), candidate.id()).stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            List<UUID> unavailable = requested.keySet().stream().filter(sku -> availability.getOrDefault(sku, BigDecimal.ZERO).compareTo(requested.get(sku)) < 0).toList();
            if (unavailable.isEmpty()) return candidate;
        }
        throw new IllegalStateException("Warehouse serviceability conflict: no single warehouse can fulfill SKUs " + requested.keySet());
    }

    private void submitPurchaseRequest(CurrentAccessContext context, DraftRow draft) {
        UUID requestId = draft.id();
        String code = "PR-" + requestId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String addressSnapshot = jdbc.query("select address_snapshot::text from sales.purchase_request_draft_destination where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> rs.getString(1), tenant(context), workspace(context), draft.id()).stream().findFirst().orElse("{}");
        String routeSnapshot = jdbc.query("select route_snapshot::text from sales.purchase_request_draft_route where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> rs.getString(1), tenant(context), workspace(context), draft.id()).stream().findFirst().orElse("{}");
        String warehouseSnapshot = jdbc.query("select selection_snapshot::text from sales.purchase_request_draft_warehouse_selection where tenant_id=? and workspace_id=? and draft_id=?", (rs, n) -> rs.getString(1), tenant(context), workspace(context), draft.id()).stream().findFirst().orElse("{}");
        Instant now = Instant.now();
        jdbc.update("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,requested_delivery_date,delivery_profile_snapshot,payment_option,comments,created_at,updated_at,submitted_at,version,delivery_address_snapshot,route_snapshot,warehouse_selection_snapshot) values (?,?,?,?,?,?,'SUBMITTED','NORMAL',?,?,?,null,?,?,?,0,?::jsonb,?::jsonb,?::jsonb) on conflict (id) do nothing", requestId, tenant(context), workspace(context), draft.clientAccountId(), draft.buyerMembershipId(), code, draft.requestedDeliveryDate(), addressSnapshot, draft.paymentPreference(), Timestamp.from(draft.createdAt()), Timestamp.from(now), Timestamp.from(now), addressSnapshot, routeSnapshot, warehouseSnapshot);
        List<SubmittedLine> lines = jdbc.query("select coalesce(nullif(s.legacy_catalog_item_id,''),s.sku_code),f.id,f.family_code,s.sku_code,s.presentation,l.quantity,l.unit,l.effective_unit_price,l.currency,l.notes,l.sku_id from sales.purchase_request_draft_line l join catalog_management.sellable_sku s on s.tenant_id=l.tenant_id and s.workspace_id=l.workspace_id and s.id=l.sku_id join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id where l.tenant_id=? and l.workspace_id=? and l.draft_id=? order by l.created_at,l.id", (rs, n) -> new SubmittedLine(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9), rs.getString(10), rs.getObject(11, UUID.class)), tenant(context), workspace(context), draft.id());
        jdbc.batchUpdate("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,product_family_id,product_family_code_snapshot,sku_id,sku_code_snapshot,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,0) on conflict (purchase_request_id,catalog_item_id) do nothing", lines, lines.size(), (ps, line) -> {
            ps.setObject(1, UUID.randomUUID()); ps.setObject(2, requestId); ps.setString(3, line.catalogItemId()); ps.setObject(4, line.familyId()); ps.setString(5, line.familyCode()); ps.setObject(6, line.skuId()); ps.setString(7, line.skuCode()); ps.setString(8, line.presentation()); ps.setString(9, line.presentation()); ps.setBigDecimal(10, line.quantity()); ps.setString(11, line.unit()); ps.setBigDecimal(12, line.amount()); ps.setString(13, line.currency()); ps.setString(14, line.notes()); ps.setTimestamp(15, Timestamp.from(now)); ps.setTimestamp(16, Timestamp.from(now));
        });
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("purchaseRequestId", requestId); payload.put("draftId", draft.id()); payload.put("clientAccountId", draft.clientAccountId()); payload.put("status", "SUBMITTED");
        CanonicalOutbox.append(jdbc, "PURCHASE_REQUEST_SUBMITTED", "PurchaseRequest", requestId, tenant(context), workspace(context), now,
                "purchase-request-" + requestId, null, SCHEMA, payload);
    }
    private void requireBuyerClient(CurrentAccessContext context, UUID clientId) { if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?)", Boolean.class, tenant(context), workspace(context), clientId, context.membershipId().value()))) throw new IllegalArgumentException("Client account is outside buyer scope"); }
    private static void buyerRead(CurrentAccessContext context) { if (!context.hasRole(MembershipRole.BUYER)) throw new IllegalStateException("Buyer surface required"); context.requirePermission(PermissionKey.BUYER_SALES_READ); }
    private static void buyerWrite(CurrentAccessContext context) { if (!context.hasRole(MembershipRole.BUYER)) throw new IllegalStateException("Buyer surface required"); context.requirePermission(PermissionKey.BUYER_SALES_WRITE); }
    private static UUID tenant(CurrentAccessContext c) { return c.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext c) { return c.workspaceId().value(); }
    private String json(Map<String, Object> value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Snapshot serialization failed", e); } }
    private static String nullSafe(String value) { return value == null ? "" : value; }
    private static String requestHash(UUID draftId, long expectedVersion) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((draftId + "|" + expectedVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record DraftRow(UUID id, UUID clientAccountId, UUID buyerMembershipId, String status, long version, LocalDate requestedDeliveryDate, String paymentPreference, String creditResult, String routeProvider, Instant createdAt, Instant updatedAt, Instant submittedAt) { }
    private record IdempotencyClaim(String requestHash, UUID draftId) { }
    private record PriceRow(UUID skuId, UUID familyId, String familyCode, String skuCode, String presentation, BigDecimal amount, String currency) { }
    private record WarehouseRow(UUID id, String code, String name) { }
    private record AddressCoordinates(BigDecimal latitude, BigDecimal longitude) { }
    private record SubmittedLine(String catalogItemId, UUID familyId, String familyCode, String skuCode, String presentation, BigDecimal quantity, String unit, BigDecimal amount, String currency, String notes, UUID skuId) { }
    private record AddressRow(UUID id, String recipient, String phone, String roadType, String street, String number, String interior, String department, String province, String district, String postalCode, String reference, String instructions, String hours, BigDecimal latitude, BigDecimal longitude, String placeId, String source) {
        AddressRow(java.sql.ResultSet rs) throws java.sql.SQLException { this(rs.getObject("id", UUID.class), rs.getString("recipient_name"), rs.getString("recipient_phone"), rs.getString("road_type"), rs.getString("street_name"), rs.getString("street_number"), rs.getString("interior"), rs.getString("department_code"), rs.getString("province_code"), rs.getString("district_code"), rs.getString("postal_code"), rs.getString("reference"), rs.getString("receiving_instructions"), rs.getString("receiving_hours"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("place_id"), rs.getString("source")); }
    }
}
