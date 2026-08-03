package com.nexa.api.warehouse.presentation;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class WarehouseController {
    private static final String ACCESS = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final WarehouseOperationsService service;

    public WarehouseController(WarehouseOperationsService service) { this.service = service; }

    @GetMapping("/warehouses")
    public PageResponse<WarehouseResponse> warehouses(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "code,asc") String sort) {
        return page(service.warehouses(c, page, size, sort), this::toWarehouse);
    }

    @GetMapping("/warehouses/{id}")
    public ResponseEntity<WarehouseResponse> warehouse(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        WarehouseResponse value = toWarehouse(service.warehouse(c, id));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @GetMapping({"/warehouses/{id}/location", "/warehouses/{id}/locations"})
    public ResponseEntity<LocationResponse> location(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        OperationalProfileResponse value = operational(service.operationalProfile(c, id));
        return ResponseEntity.ok().eTag(etag(value.version()))
                .body(new LocationResponse(value.id(), value.address(), value.latitude(), value.longitude(), value.version()));
    }

    @GetMapping({"/warehouses/{id}/profile", "/warehouses/{id}/operational-profile"})
    public ResponseEntity<OperationalProfileResponse> operationalProfile(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        OperationalProfileResponse value = operational(service.operationalProfile(c, id));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @GetMapping("/warehouses/{id}/hours")
    public ResponseEntity<OperationalProfileResponse> hours(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        OperationalProfileResponse value = operational(service.operationalProfile(c, id));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @GetMapping("/warehouses/{id}/serviceability")
    public ResponseEntity<OperationalProfileResponse> serviceability(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        OperationalProfileResponse value = operational(service.operationalProfile(c, id));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @GetMapping("/warehouses/{id}/selection-policy")
    public ResponseEntity<OperationalProfileResponse> selectionPolicy(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        OperationalProfileResponse value = operational(service.operationalProfile(c, id));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping({"/warehouses/{id}/profile", "/warehouses/{id}/operational-profile"})
    public ResponseEntity<OperationalProfileResponse> updateOperationalProfile(@RequestAttribute(ACCESS) CurrentAccessContext c,
                                                                                 @PathVariable String id,
                                                                                 @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                                 @RequestBody OperationalPatchRequest request) {
        OperationalProfileResponse value = operational(service.updateOperationalProfile(c, id, request.toPatch(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping("/warehouses/{id}/hours")
    public ResponseEntity<OperationalProfileResponse> updateHours(@RequestAttribute(ACCESS) CurrentAccessContext c,
                                                                  @PathVariable String id,
                                                                  @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                  @RequestBody HoursPatchRequest request) {
        OperationalPatchRequest patch = new OperationalPatchRequest(null, null, null, null,
                request.operatingHoursStart(), request.operatingHoursEnd(), null);
        OperationalProfileResponse value = operational(service.updateOperationalProfile(c, id, patch.toPatch(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping("/warehouses/{id}/serviceability")
    public ResponseEntity<OperationalProfileResponse> updateServiceability(@RequestAttribute(ACCESS) CurrentAccessContext c,
                                                                            @PathVariable String id,
                                                                            @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                            @RequestBody ServiceabilityPatchRequest request) {
        OperationalPatchRequest patch = new OperationalPatchRequest(null, null, null, null, null, null, request.serviceable());
        OperationalProfileResponse value = operational(service.updateOperationalProfile(c, id, patch.toPatch(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PatchMapping("/warehouses/{id}/selection-policy")
    public ResponseEntity<OperationalProfileResponse> updateSelectionPolicy(@RequestAttribute(ACCESS) CurrentAccessContext c,
                                                                             @PathVariable String id,
                                                                             @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                             @RequestBody SelectionPolicyPatchRequest request) {
        OperationalPatchRequest patch = new OperationalPatchRequest(null, null, null, request.selectionPolicy(), null, null, null);
        OperationalProfileResponse value = operational(service.updateOperationalProfile(c, id, patch.toPatch(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @GetMapping("/buyer/warehouses")
    public List<BuyerWarehouseResponse> buyerWarehouses(@RequestAttribute(ACCESS) CurrentAccessContext c) {
        return service.buyerWarehouses(c).stream()
                .map(value -> new BuyerWarehouseResponse(value.code(), value.name(), value.address(),
                        value.operatingHoursStart(), value.operatingHoursEnd(), value.serviceable()))
                .toList();
    }

    @PostMapping("/warehouses")
    public ResponseEntity<WarehouseResponse> createWarehouse(@RequestAttribute(ACCESS) CurrentAccessContext c, @Valid @RequestBody WarehouseRequest r) {
        var result = toWarehouse(service.createWarehouse(c, r.code(), r.name(), r.address()));
        return ResponseEntity.status(201).eTag(etag(result.version())).body(result);
    }

    @PatchMapping("/warehouses/{id}")
    public ResponseEntity<WarehouseResponse> updateWarehouse(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody WarehousePatch r) {
        var result = toWarehouse(service.updateWarehouse(c, id, r.name(), r.address(), r.status(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(result.version())).body(result);
    }

    @PatchMapping({"/warehouses/{id}/location", "/warehouses/{id}/locations"})
    public ResponseEntity<LocationResponse> updateLocation(@RequestAttribute(ACCESS) CurrentAccessContext c,
                                                            @PathVariable String id,
                                                            @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                            @RequestBody LocationPatchRequest request) {
        OperationalPatchRequest patch = new OperationalPatchRequest(null, request.address(), null, null, null, null, null,
                request.latitude(), request.longitude());
        OperationalProfileResponse value = operational(service.updateOperationalProfile(c, id, patch.toPatch(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(value.version()))
                .body(new LocationResponse(value.id(), value.address(), value.latitude(), value.longitude(), value.version()));
    }

    @GetMapping("/warehouses/{warehouseId}/zones")
    public PageResponse<ZoneResponse> zones(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String warehouseId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return page(service.zones(c, warehouseId, page, size), this::zone);
    }

    @PostMapping("/warehouses/{warehouseId}/zones")
    public ResponseEntity<ZoneResponse> createZone(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String warehouseId, @RequestBody ZoneRequest r) {
        var result = zone(service.createZone(c, warehouseId, r.code(), r.name(), r.type(), r.temperatureMin(), r.temperatureMax()));
        return ResponseEntity.status(201).eTag(etag(result.version())).body(result);
    }

    @PatchMapping("/warehouses/{warehouseId}/zones/{zoneId}")
    public ResponseEntity<ZoneResponse> updateZone(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String warehouseId, @PathVariable String zoneId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody ZonePatch r) {
        var result = zone(service.updateZone(c, warehouseId, zoneId, r.name(), r.temperatureMin(), r.temperatureMax(), r.status(), version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(result.version())).body(result);
    }

    @GetMapping({"/inventory", "/inventory/lots"})
    public PageResponse<LotResponse> inventory(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(required = false) String catalogItemId, @RequestParam(required = false) String warehouseId, @RequestParam(required = false) String zoneId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "expirationDate,asc") String sort) {
        return page(service.lots(c, catalogItemId, warehouseId, zoneId, status, page, size, sort), this::lot);
    }

    @GetMapping("/inventory/lots/{lotId}")
    public LotResponse lot(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String lotId) { return lot(service.lot(c, lotId)); }

    @GetMapping("/inventory/movements")
    public PageResponse<MovementResponse> movements(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(required = false) String lotId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "occurredAt,desc") String sort) {
        return page(service.movements(c, lotId, page, size, sort), this::movement);
    }

    @PostMapping("/inventory/inbound-receipts")
    public ResponseEntity<LotResponse> receive(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReceiptRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        var result = lot(service.receive(c, new WarehouseOperationsService.Receipt(r.warehouseId(), r.zoneId(), r.catalogItemId(), r.batchNumber(), r.expirationDate(), r.quantity(), r.unit(), r.temperatureReading(), r.notes()), key, String.valueOf(correlation)));
        return ResponseEntity.status(201).eTag(etag(result.version())).body(result);
    }

    @PostMapping("/inventory/adjustments")
    public ResponseEntity<LotResponse> adjust(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody QuantityRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        var result = lot(service.adjust(c, r.lotId(), r.quantity(), r.direction() == null || r.direction().equalsIgnoreCase("IN"), r.reason(), version(ifMatch), key, String.valueOf(correlation)));
        return ResponseEntity.ok().eTag(etag(result.version())).body(result);
    }

    @PostMapping("/inventory/waste-movements")
    public ResponseEntity<LotResponse> waste(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody QuantityRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        var result = lot(service.waste(c, r.lotId(), r.quantity(), r.reason(), version(ifMatch), key, String.valueOf(correlation)));
        return ResponseEntity.ok().eTag(etag(result.version())).body(result);
    }

    @PostMapping("/inventory/lots/{lotId}/blocks")
    public ResponseEntity<LotResponse> blockLot(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String lotId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReasonRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        return mutation(service.blockLot(c, lotId, version(ifMatch), r.reason(), key, String.valueOf(correlation)));
    }

    @PostMapping("/inventory/lots/{lotId}/quarantines")
    public ResponseEntity<LotResponse> quarantineLot(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String lotId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReasonRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        return mutation(service.quarantineLot(c, lotId, version(ifMatch), r.reason(), key, String.valueOf(correlation)));
    }

    @PostMapping("/inventory/lots/{lotId}/availability-restorations")
    public ResponseEntity<LotResponse> restoreLot(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String lotId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReasonRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        return mutation(service.restoreLot(c, lotId, version(ifMatch), r.reason(), key, String.valueOf(correlation)));
    }

    @GetMapping("/inventory-availability")
    public List<AvailabilityResponse> availability(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(required = false) String catalogItemId, @RequestParam(required = false) List<String> catalogItemIds) {
        return service.availability(c, catalogItemIds != null && !catalogItemIds.isEmpty() ? catalogItemIds : List.of(catalogItemId)).stream().map(this::availability).toList();
    }

    @GetMapping("/fulfillment-candidates/{salesOrderId}/inventory-reservation-preview")
    public ReservationPreviewResponse preview(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String salesOrderId) { return preview(service.preview(c, salesOrderId)); }

    @PostMapping("/fulfillment-candidates/{salesOrderId}/inventory-reservations")
    public ResponseEntity<ReservationDetailResponse> reserve(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String salesOrderId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        var result = reservation(service.reserve(c, salesOrderId, version(ifMatch), key, String.valueOf(correlation)));
        return ResponseEntity.status("RESERVED".equals(result.status()) ? 201 : 409).eTag(etag(result.version())).body(result);
    }

    @GetMapping("/inventory-reservations")
    public PageResponse<ReservationSummaryResponse> reservations(@RequestAttribute(ACCESS) CurrentAccessContext c, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return page(service.reservations(c, status, page, size), this::reservationSummary);
    }

    @GetMapping("/inventory-reservations/{id}")
    public ReservationDetailResponse reservation(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) { return reservation(service.reservation(c, id)); }

    @PostMapping("/inventory-reservations/{id}/releases")
    public ResponseEntity<ReservationDetailResponse> release(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReasonRequest r, @RequestAttribute(value = "com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId", required = false) Object correlation) {
        var result = reservation(service.release(c, id, version(ifMatch), key, r.reason(), String.valueOf(correlation), false));
        return ResponseEntity.ok().eTag(etag(result.version())).body(result);
    }

    @GetMapping("/dispatch-readiness-candidates")
    public List<ReadinessCandidateResponse> readiness(@RequestAttribute(ACCESS) CurrentAccessContext c) { return service.readiness(c).stream().map(this::readiness).toList(); }

    @GetMapping("/dispatch-readiness-candidates/{id}")
    public ReadinessCandidateResponse readinessOne(@RequestAttribute(ACCESS) CurrentAccessContext c, @PathVariable String id) {
        return service.readiness(c).stream().filter(x -> x.reservationId().equals(id)).map(this::readiness).findFirst().orElseThrow(() -> new WarehouseOperationsService.WarehouseException("DISPATCH_READINESS_CANDIDATE_NOT_FOUND", true));
    }

    private ResponseEntity<LotResponse> mutation(WarehouseOperationsService.LotSummary value) { LotResponse result = lot(value); return ResponseEntity.ok().eTag(etag(result.version())).body(result); }
    private static long version(String value) { if (value == null || value.isBlank()) throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false); try { long parsed = Long.parseLong(value.replace("\"", "")); if (parsed < 0) throw new NumberFormatException(); return parsed; } catch (NumberFormatException e) { throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false); } }
    private static String etag(long value) { return "\"" + value + "\""; }
    private <T, R> PageResponse<R> page(WarehouseOperationsService.Page<T> value, Function<T, R> mapper) { return new PageResponse<>(value.items().stream().map(mapper).toList(), value.page(), value.size(), value.total()); }
    private WarehouseResponse toWarehouse(WarehouseOperationsService.WarehouseSummary x) { return new WarehouseResponse(x.id(), x.code(), x.name(), x.address(), x.status(), x.version()); }
    private OperationalProfileResponse operational(WarehouseOperationsService.OperationalProfile x) {
        return new OperationalProfileResponse(x.id(), x.code(), x.name(), x.address(), x.status(), x.operatingHoursStart(),
                x.operatingHoursEnd(), x.serviceable(), x.selectionPolicy(), x.version(), x.settingsVersion(),
                x.latitude(), x.longitude());
    }
    private ZoneResponse zone(WarehouseOperationsService.ZoneSummary x) { return new ZoneResponse(x.id(), x.warehouseId(), x.code(), x.name(), x.type(), x.temperatureMin(), x.temperatureMax(), x.status(), x.version()); }
    private LotResponse lot(WarehouseOperationsService.LotSummary x) { return new LotResponse(x.id(), x.warehouseId(), x.zoneId(), x.catalogItemId(), x.batchNumber(), x.expirationDate(), x.receivedAt(), x.onHand(), x.reserved(), x.available(), x.unit(), x.status(), x.version()); }
    private MovementResponse movement(WarehouseOperationsService.MovementSummary x) { return new MovementResponse(x.id(), x.lotId(), x.catalogItemId(), x.type(), x.quantity(), x.unit(), x.quantityBefore(), x.quantityAfter(), x.reservedBefore(), x.reservedAfter(), x.reason(), x.occurredAt()); }
    private AvailabilityResponse availability(WarehouseOperationsService.Availability x) { return new AvailabilityResponse(x.catalogItemId(), x.status(), x.asOf()); }
    private ReservationPreviewResponse preview(WarehouseOperationsService.ReservationPreview x) { return new ReservationPreviewResponse(x.salesOrderId(), x.orderNumber(), x.lines().stream().map(this::proposal).toList(), x.complete(), x.generatedAt(), x.notice()); }
    private ProposalLineResponse proposal(WarehouseOperationsService.ProposalLine x) { return new ProposalLineResponse(x.catalogItemId(), x.requested(), x.unit(), x.allocations().stream().map(this::allocation).toList(), x.shortage(), x.complete()); }
    private ReservationDetailResponse reservation(WarehouseOperationsService.ReservationDetail x) { return new ReservationDetailResponse(x.id(), x.salesOrderId(), x.orderNumber(), x.status(), x.createdAt(), x.reservedAt(), x.expiresAt(), x.version(), x.clientAccountId(), x.allocations().stream().map(this::allocation).toList()); }
    private ReservationSummaryResponse reservationSummary(WarehouseOperationsService.ReservationSummary x) { return new ReservationSummaryResponse(x.id(), x.salesOrderId(), x.orderNumber(), x.status(), x.createdAt(), x.reservedAt(), x.expiresAt(), x.version()); }
    private AllocationResponse allocation(WarehouseOperationsService.AllocationView x) { return new AllocationResponse(x.lotId(), x.quantity(), x.unit(), x.expirationDate()); }
    private ReadinessCandidateResponse readiness(WarehouseOperationsService.ReadinessCandidate x) { return new ReadinessCandidateResponse(x.reservationId(), x.salesOrderId(), x.orderNumber(), x.clientAccountId(), x.lineCount(), x.totalReservedQuantity(), x.reservedAt(), x.expiresAt(), x.readinessStatus()); }

    public record PageResponse<T>(List<T> items, int page, int size, long total) { }
    public record WarehouseResponse(String id, String code, String name, String address, String status, long version) { }
    public record LocationResponse(String warehouseId, String address, BigDecimal latitude, BigDecimal longitude, long version) { }
    public record OperationalProfileResponse(String id, String code, String name, String address, String status,
                                             LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                             boolean serviceable, String selectionPolicy, long version,
                                             long settingsVersion, BigDecimal latitude, BigDecimal longitude) { }
    /** Buyer-safe projection: no internal warehouse id, stock, score or version crosses the boundary. */
    public record BuyerWarehouseResponse(String code, String name, String address,
                                         LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                         boolean serviceable) { }
    public record ZoneResponse(String id, String warehouseId, String code, String name, String type, BigDecimal temperatureMin, BigDecimal temperatureMax, String status, long version) { }
    public record LotResponse(String id, String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, Instant receivedAt, BigDecimal onHand, BigDecimal reserved, BigDecimal available, String unit, String status, long version) { }
    public record MovementResponse(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt) { }
    public record AvailabilityResponse(String catalogItemId, String status, Instant asOf) { }
    public record ReservationPreviewResponse(String salesOrderId, String orderNumber, List<ProposalLineResponse> lines, boolean complete, Instant generatedAt, String notice) { }
    public record ProposalLineResponse(String catalogItemId, BigDecimal requested, String unit, List<AllocationResponse> allocations, BigDecimal shortage, boolean complete) { }
    public record AllocationResponse(String lotId, BigDecimal quantity, String unit, LocalDate expirationDate) { }
    public record ReservationSummaryResponse(String id, String salesOrderId, String orderNumber, String status, Instant createdAt, Instant reservedAt, Instant expiresAt, long version) { }
    public record ReservationDetailResponse(String id, String salesOrderId, String orderNumber, String status, Instant createdAt, Instant reservedAt, Instant expiresAt, long version, String clientAccountId, List<AllocationResponse> allocations) { }
    public record ReadinessCandidateResponse(String reservationId, String salesOrderId, String orderNumber, String clientAccountId, int lineCount, BigDecimal totalReservedQuantity, Instant reservedAt, Instant expiresAt, String readinessStatus) { }

    public record WarehouseRequest(String code, String name, String address) { }
    public record LocationPatchRequest(String address, BigDecimal latitude, BigDecimal longitude) { }
    public record WarehousePatch(String name, String address, String status) { }
    public record OperationalPatchRequest(String name, String address, String status, String selectionPolicy,
                                          LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                          Boolean serviceable, BigDecimal latitude, BigDecimal longitude) {
        public OperationalPatchRequest(String name, String address, String status, String selectionPolicy,
                                       LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                       Boolean serviceable) {
            this(name, address, status, selectionPolicy, operatingHoursStart, operatingHoursEnd, serviceable, null, null);
        }
        WarehouseOperationsService.OperationalPatch toPatch() {
            return new WarehouseOperationsService.OperationalPatch(name, address, status, selectionPolicy,
                    operatingHoursStart, operatingHoursEnd, serviceable, latitude, longitude);
        }
    }
    public record HoursPatchRequest(LocalTime operatingHoursStart, LocalTime operatingHoursEnd) { }
    public record ServiceabilityPatchRequest(Boolean serviceable) { }
    public record SelectionPolicyPatchRequest(String selectionPolicy) { }
    public record ZoneRequest(String code, String name, String type, BigDecimal temperatureMin, BigDecimal temperatureMax) { }
    public record ZonePatch(String name, BigDecimal temperatureMin, BigDecimal temperatureMax, String status) { }
    public record ReceiptRequest(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes) { }
    public record QuantityRequest(String lotId, BigDecimal quantity, String direction, String reason) { }
    public record ReasonRequest(String reason) { }
}
