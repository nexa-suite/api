package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchQueryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@Profile("!test")
public class DispatchQueryPersistenceAdapter extends LogisticsJdbcSupport implements DispatchQueryPersistencePort {
    public DispatchQueryPersistenceAdapter(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                           com.nexa.api.shared.application.port.out.ChangeEventPersistencePort changeFeed,
                                           com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        super(jdbc, changeFeed, warehouseFulfillment);
    }
    public LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> list(String t, String w, String c, String status, int p, int s, String sort) { return super.list(t, w, c, status, p, s, sort); }
    public LogisticsOperationsService.DispatchView detail(String t, String w, String c, String id) { return super.detail(t, w, c, id); }
    public java.util.List<LogisticsOperationsService.DispatchEventView> events(String t, String w, String c, String id) { return super.events(t, w, c, id); }
    public LogisticsOperationsService.DashboardView dashboard(String t, String w) { return super.dashboard(t, w); }
    public LogisticsOperationsService.AnalyticsView analytics(String t, String w, Instant from, Instant to) { return super.analytics(t, w, from, to); }
    public LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> proofOfDelivery(String t, String w, String status, int p, int s) { return super.proofOfDelivery(t, w, status, p, s); }
}
