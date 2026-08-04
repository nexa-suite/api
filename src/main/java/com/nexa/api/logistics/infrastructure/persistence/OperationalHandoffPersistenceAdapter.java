package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.OperationalHandoffPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("!test")
public class OperationalHandoffPersistenceAdapter extends LogisticsJdbcSupport implements OperationalHandoffPort {
    public OperationalHandoffPersistenceAdapter(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                com.nexa.api.shared.application.port.out.ChangeEventPersistencePort changeFeed,
                                                com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                                com.nexa.api.logistics.application.port.OperationalHandoffNotificationPort handoffNotifications) {
        super(jdbc, changeFeed, warehouseFulfillment, handoffNotifications);
    }
    public List<LogisticsOperationsService.HandoffNoteView> notes(String t, String w, String c, String id) { return super.notes(t, w, c, id); }
    public LogisticsOperationsService.HandoffNoteView append(String t, String w, String id, long v, String actor, String key, String note, long now) { return super.append(t, w, id, v, actor, key, note, now); }
}
