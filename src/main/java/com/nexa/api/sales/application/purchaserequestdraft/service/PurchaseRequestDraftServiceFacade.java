package com.nexa.api.sales.application.purchaserequestdraft.service;

import com.nexa.api.sales.application.purchaserequestdraft.model.PurchaseRequestDraftModels;
import com.nexa.api.sales.application.port.PurchaseRequestDraftPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
public class PurchaseRequestDraftServiceFacade {
    private final PurchaseRequestDraftPort port;
    public PurchaseRequestDraftServiceFacade(PurchaseRequestDraftPort port) { this.port = port; }
    public PurchaseRequestDraftModels.DraftView create(CurrentAccessContext c, UUID clientAccountId, LocalDate date) { return port.create(c, clientAccountId, date); }
    public PurchaseRequestDraftModels.DraftView get(CurrentAccessContext c, UUID id) { return port.get(c, id); }
    public PurchaseRequestDraftModels.DraftView replaceLines(CurrentAccessContext c, UUID id, long version, List<LineCommand> lines) { return port.replaceLines(c, id, version, lines.stream().map(line -> new PurchaseRequestDraftPort.LineCommand(line.skuId(), line.quantity(), line.unit(), line.notes())).toList()); }
    public PurchaseRequestDraftModels.DraftView setDestination(CurrentAccessContext c, UUID id, long version, UUID addressId) { return port.setDestination(c, id, version, addressId); }
    public PurchaseRequestDraftModels.DraftView previewRoute(CurrentAccessContext c, UUID id, long version, String provider) { return port.previewRoute(c, id, version, provider); }
    public PurchaseRequestDraftModels.DraftView setPreferences(CurrentAccessContext c, UUID id, long version, String payment, LocalDate date) { return port.setPreferences(c, id, version, payment, date); }
    public PurchaseRequestDraftModels.ReviewView review(CurrentAccessContext c, UUID id) { return port.review(c, id); }
    public PurchaseRequestDraftModels.DraftView submit(CurrentAccessContext c, UUID id, long version, String key) { return port.submit(c, id, version, key); }
    public record LineCommand(UUID skuId, BigDecimal quantity, String unit, String notes) { }
}
