package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchRouteStartPort;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("!test")
public class DispatchRouteStartPersistenceAdapter extends LogisticsJdbcSupport implements DispatchRouteStartPort {
    public DispatchRouteStartPersistenceAdapter(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                com.nexa.api.shared.application.port.out.ChangeEventPersistencePort changeFeed,
                                                com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        super(jdbc, changeFeed, warehouseFulfillment);
    }
    public Optional<LogisticsOperationsService.DispatchView> replayRouteStart(String t, String w, String k, String h) { return super.replayRouteStart(t, w, k, h); }
    public Optional<DispatchOrder> findDispatchForRouteStart(String t, String w, String id) { return super.findDispatchForRouteStart(t, w, id); }
    public LogisticsOperationsService.DispatchView commitRouteStart(String t, String w, DispatchOrder aggregate, DispatchStatus expected, long version, String actor, String key, String hash, long now) { return super.commitRouteStart(t, w, aggregate, expected, version, actor, key, hash, now); }
}
