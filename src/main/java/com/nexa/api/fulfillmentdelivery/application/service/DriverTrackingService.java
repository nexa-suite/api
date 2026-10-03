package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.DriverTrackingPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!test")
public class DriverTrackingService {
    private final DriverTrackingPort tracking;
    private final WorkforceDirectory workforce;
    private final CustomerAccountQuery accounts;
    private final SalesOrderFulfillmentQuery orders;
    private final Clock clock;
    public DriverTrackingService(DriverTrackingPort tracking, WorkforceDirectory workforce,
                                 CustomerAccountQuery accounts, SalesOrderFulfillmentQuery orders, Clock clock) {
        this.tracking=tracking; this.workforce=workforce; this.accounts=accounts; this.orders=orders; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Workday current(CurrentAccessContext c) { driver(c); return tracking.current(t(c),w(c),a(c)); }
    @Transactional
    public Workday command(CurrentAccessContext c, UUID day, Long version, String action, String key) {
        driver(c);
        if (key==null || key.isBlank() || key.length()>160) throw error("IDEMPOTENCY_KEY_REQUIRED",false);
        if (!Set.of("START","END","AVAILABLE","UNAVAILABLE").contains(action)) throw error("INVALID_REQUEST",false);
        if (!"START".equals(action) && (day==null || version==null || version<0)) throw error("PRECONDITION_REQUIRED",false);
        return tracking.command(new WorkdayCommand(t(c),w(c),a(c),day,version,action,key,
                hash(action+"|"+day+"|"+version),clock.instant()));
    }
    @Transactional
    public Coordinate capture(CurrentAccessContext c,UUID day, UUID sampleId, double lat,double lon,
                              double accuracy,Instant capturedAt) {
        driver(c);
        if(day==null || sampleId==null) throw error("INVALID_REQUEST",false);
        return tracking.capture(new SampleCommand(t(c),w(c),a(c),day,
                new Coordinate(sampleId,lat,lon,accuracy,capturedAt,null),clock.instant()));
    }
    @Transactional(readOnly=true)
    public Coordinate ownLocation(CurrentAccessContext c) { driver(c); return tracking.latest(t(c),w(c),a(c),clock.instant()); }
    @Transactional(readOnly=true)
    public Coordinate dispatchLocation(CurrentAccessContext c,UUID driver) {
        c.requirePermission(PermissionKey.DISPATCH_READ);
        if (!(c.hasRole(MembershipRole.COMPANY_OWNER) || c.hasRole(MembershipRole.LOGISTICS)))
            throw error("DRIVER_LOCATION_NOT_FOUND",true);
        c.requirePermission(PermissionKey.DISPATCH_ASSIGN);
        if (workforce.findAssignableLogisticsName(t(c),w(c),driver).isEmpty()) throw error("DRIVER_LOCATION_NOT_FOUND",true);
        return tracking.latest(t(c),w(c),driver,clock.instant());
    }
    @Transactional(readOnly=true)
    public DeliveryTracking buyerLocation(CurrentAccessContext c,UUID delivery) {
        c.requirePermission(PermissionKey.BUYER_TRACKING_READ);
        if (!c.hasRole(MembershipRole.BUYER)) throw error("DELIVERY_NOT_FOUND",true);
        DeliveryScope scope=tracking.deliveryScope(t(c),w(c),delivery);
        if (scope==null || !Set.of("DISPATCHED","IN_TRANSIT","PARTIAL").contains(scope.status())
                || scope.salesOrderId()==null || scope.driverMembershipId()==null) throw error("DELIVERY_NOT_FOUND",true);
        var account=orders.get(t(c),w(c),scope.salesOrderId()).clientAccountId();
        if(account==null || !accounts.hasBuyerRelationship(t(c).toString(),w(c).toString(),a(c).toString(),account.toString()))
            throw error("DELIVERY_NOT_FOUND",true);
        return new DeliveryTracking(delivery,tracking.latest(t(c),w(c),scope.driverMembershipId(),clock.instant()));
    }
    private void driver(CurrentAccessContext c) {
        c.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        if(workforce.findAssignableLogisticsName(t(c),w(c),a(c)).isEmpty()) throw error("DRIVER_WORKDAY_NOT_FOUND",true);
    }
    private static UUID t(CurrentAccessContext c){return c.tenantId().value();}
    private static UUID w(CurrentAccessContext c){return c.workspaceId().value();}
    private static UUID a(CurrentAccessContext c){return c.membershipId().value();}
    public static FulfillmentOperationException error(String code,boolean hidden){return new FulfillmentOperationException(code,hidden);}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
