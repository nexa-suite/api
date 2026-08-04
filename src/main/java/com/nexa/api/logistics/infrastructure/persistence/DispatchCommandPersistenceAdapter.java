package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchCommandPersistencePort;
import com.nexa.api.logistics.application.port.OperationalHandoffNotificationPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;

@Repository
@Profile("!test")
public class DispatchCommandPersistenceAdapter extends LogisticsJdbcSupport implements DispatchCommandPersistencePort {
    @org.springframework.beans.factory.annotation.Autowired
    public DispatchCommandPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                              WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                              OperationalHandoffNotificationPort handoffNotifications) {
        super(jdbc, changeFeed, warehouseFulfillment, handoffNotifications);
    }
    public LogisticsOperationsService.DispatchView create(String t, String w, String r, long rv, String a, String k, long n) { return super.create(t, w, r, rv, a, k, n); }
    public LogisticsOperationsService.DispatchView prepare(String t, String w, String id, long v, String a, String k, long n) { return super.prepare(t, w, id, v, a, k, n); }
    public LogisticsOperationsService.DispatchView assign(String t, String w, String id, long v, String a, String k, String m, String vehicle, String route, long n) { return super.assign(t, w, id, v, a, k, m, vehicle, route, n); }
    public LogisticsOperationsService.DispatchView schedule(String t, String w, String id, long v, String a, String k, Instant start, Instant end, Instant eta, long n) { return super.schedule(t, w, id, v, a, k, start, end, eta, n); }
    public LogisticsOperationsService.DispatchView ready(String t, String w, String id, long v, String a, String k, long n) { return super.ready(t, w, id, v, a, k, n); }
    public LogisticsOperationsService.DispatchView temperature(String t, String w, String id, long v, String a, String k, BigDecimal value, String unit, Instant at, String source, long n) { return super.temperature(t, w, id, v, a, k, value, unit, at, source, n); }
    public LogisticsOperationsService.DispatchView incident(String t, String w, String id, long v, String a, String k, String type, String severity, boolean visible, String description, Instant at, String resolution, long n) { return super.incident(t, w, id, v, a, k, type, severity, visible, description, at, resolution, n); }
    public LogisticsOperationsService.DispatchView reprogram(String t, String w, String id, long v, String a, String k, Instant start, Instant end, Instant eta, String reason, long n) { return super.reprogram(t, w, id, v, a, k, start, end, eta, reason, n); }
    public LogisticsOperationsService.DispatchView cancel(String t, String w, String id, long v, String a, String k, String reason, long n) { return super.cancel(t, w, id, v, a, k, reason, n); }
    public LogisticsOperationsService.DispatchView complete(String t, String w, String id, long v, String a, String k, String receiver, Instant at, String notes, boolean photo, boolean signature, long n) { return super.complete(t, w, id, v, a, k, receiver, at, notes, photo, signature, n); }
}
