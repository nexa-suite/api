package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.ExecutionTemperaturePort;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@Profile("!test")
public class ExecutionTemperatureService {
    private final ExecutionTemperaturePort persistence;
    private final ColdChainPolicyQuery policy;
    private final BusinessEvidenceQuery evidence;
    private final WarehouseObjectAccess warehouses;
    private final Clock clock;
    public ExecutionTemperatureService(ExecutionTemperaturePort persistence, ColdChainPolicyQuery policy,
            BusinessEvidenceQuery evidence, WarehouseObjectAccess warehouses, Clock clock) {
        this.persistence=persistence; this.policy=policy; this.evidence=evidence; this.warehouses=warehouses; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Snapshot read(CurrentAccessContext c, UUID deliveryId, boolean driver) {
        if (driver) c.requirePermission(PermissionKey.DISPATCH_READ);
        else c.requirePermission(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE);
        Scope s=scope(c,deliveryId); Delivery d=persistence.delivery(s,driver,false);
        if (!driver) requireWarehouse(c,d);
        List<Line> lines=persistence.lines(s,d.fulfillmentId()).stream().map(l -> {
            var p=policy.temperatureRequirementForSku(s.tenantId(),s.workspaceId(),l.skuId());
            return new Line(l.id(),l.skuId(),l.unit(),l.remainingQuantity(),p.map(v->v.temperatureMin()!=null && v.temperatureMax()!=null).orElse(false),
                    p.map(v->v.temperatureMin()).orElse(null),p.map(v->v.temperatureMax()).orElse(null));
        }).toList();
        return new Snapshot(d.id(),d.version(),d.status(),d.attemptId(),d.warehouseId(),lines,persistence.holds(s));
    }
    @Transactional
    public Reading record(CurrentAccessContext c, UUID deliveryId, long version, String key, ReadingCommand command) {
        c.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        validate(version,key);
        if (command==null || command.fulfillmentLineId()==null || command.skuId()==null || command.value()==null
                || command.affectedQuantity()==null || command.affectedQuantity().signum()<=0
                || command.affectedQuantity().scale()>6 || command.affectedQuantity().precision()>19
                || command.value().scale()>6
                || !"CELSIUS".equals(command.unit()) || command.occurredAt()==null
                || command.value().abs().compareTo(new BigDecimal("1000"))>=0
) fail("INVALID_REQUEST");
        Scope s=scope(c,deliveryId); Delivery d=persistence.delivery(s,true,true);
        String hash=hash("execution-temperature-v1|"+deliveryId+"|"+version+"|"+command);
        Reading prior=persistence.replay(s,"EXECUTION_TEMPERATURE",key,hash);
        if (prior!=null) return prior;
        if (command.occurredAt().isAfter(clock.instant())) fail("INVALID_REQUEST");
        if (!Set.of("DISPATCHED","IN_TRANSIT","PARTIAL").contains(d.status())) fail("DELIVERY_NOT_ACTIVE");
        if (d.version()!=version) fail("CONCURRENCY_CONFLICT");
        RawLine line=persistence.lines(s,d.fulfillmentId()).stream().filter(l->l.id().equals(command.fulfillmentLineId())
                && l.skuId().equals(command.skuId())).findFirst().orElseThrow(()->error("DELIVERY_OUTCOME_LINE_MISMATCH"));
        if (command.affectedQuantity().compareTo(line.remainingQuantity())>0) fail("DELIVERY_OUTCOME_EXCEEDS_REMAINING");
        var p=policy.temperatureRequirementForSku(s.tenantId(),s.workspaceId(),line.skuId()).orElseThrow(()->error("TEMPERATURE_EVIDENCE_REQUIRED"));
        if (p.temperatureMin()==null || p.temperatureMax()==null) fail("TEMPERATURE_EVIDENCE_REQUIRED");
        boolean excursion=command.value().compareTo(p.temperatureMin())<0 || command.value().compareTo(p.temperatureMax())>0;
        if (excursion || command.sourceIncidentId()!=null || command.evidenceObjectId()!=null) {
            c.requirePermission(PermissionKey.DOCUMENT_READ);
            if (command.sourceIncidentId()==null || command.evidenceObjectId()==null
                    || !evidence.isAvailablePhotoForSubject(s.tenantId(),s.workspaceId(),command.evidenceObjectId(),
                        "DELIVERY_INCIDENT",command.sourceIncidentId())) fail("TEMPERATURE_EVIDENCE_REQUIRED");
        }
        return persistence.record(s,d,version,key,hash,command,p.temperatureMin(),p.temperatureMax(),excursion,clock.instant());
    }
    @Transactional
    public DispositionResult dispose(CurrentAccessContext c, UUID deliveryId, UUID holdId, long version, String key,
            Disposition disposition, String reason) {
        c.requirePermission(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE); validate(version,key);
        if (disposition==null || reason==null || reason.isBlank() || reason.trim().length()>2000) fail("INVALID_REQUEST");
        Scope s=scope(c,deliveryId); Delivery d=persistence.delivery(s,false,true); requireWarehouse(c,d);
        return persistence.dispose(s,d,holdId,version,key,hash("execution-disposition-v1|"+deliveryId+"|"+holdId+"|"+version+"|"+disposition+"|"+reason.trim()),disposition,reason.trim(),clock.instant());
    }
    private void requireWarehouse(CurrentAccessContext c, Delivery d) {
        if (d.warehouseId()==null || !warehouses.hasActiveGrant(c,d.warehouseId())) fail("FORBIDDEN");
    }
    private static Scope scope(CurrentAccessContext c,UUID id) { return new Scope(c.tenantId().value(),c.workspaceId().value(),c.membershipId().value(),id); }
    private static void validate(long version,String key) { if(version<0 || key==null || key.isBlank() || key.length()>160) fail("INVALID_REQUEST"); }
    private static FulfillmentOperationException error(String code) { return new FulfillmentOperationException(code,false); }
    private static void fail(String code) { throw error(code); }
    private static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
