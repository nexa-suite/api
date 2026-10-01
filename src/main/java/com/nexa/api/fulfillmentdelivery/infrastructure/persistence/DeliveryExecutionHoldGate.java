package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Reads only BC-06 execution facts; administrative closure never disposes goods. */
final class DeliveryExecutionHoldGate {
    private DeliveryExecutionHoldGate() { }
    static boolean blocking(JdbcTemplate jdbc,UUID tenant,UUID workspace,UUID delivery) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists("
                + "select 1 from logistics.driver_delivery_incident i where i.tenant_id=? and i.workspace_id=? "
                + "and i.delivery_id=? and i.exception_severity in('BLOCKING','CRITICAL') and not exists("
                + "select 1 from logistics.operational_exception_case c where c.tenant_id=i.tenant_id "
                + "and c.workspace_id=i.workspace_id and c.source_driver_incident_id=i.id and ("
                + "(c.severity='BLOCKING' and (select x.to_status from logistics.operational_exception_transition x "
                + "where x.tenant_id=c.tenant_id and x.workspace_id=c.workspace_id and x.exception_id=c.id "
                + "order by x.transition_number desc limit 1) in('RESOLVED','CLOSED')) "
                + "or (c.type='TEMPERATURE_EXCURSION' and exists(select 1 from logistics.delivery_execution_hold h "
                + "where h.tenant_id=c.tenant_id and h.workspace_id=c.workspace_id and h.exception_id=c.id "
                + "and (select x.disposition from logistics.delivery_execution_disposition x where x.tenant_id=h.tenant_id "
                + "and x.workspace_id=h.workspace_id and x.hold_id=h.id order by x.sequence desc limit 1)='RELEASE')))) "
                + "union all select 1 from logistics.delivery d join logistics.delivery_incident i "
                + "on i.tenant_id=d.tenant_id and i.workspace_id=d.workspace_id and i.dispatch_order_id=d.dispatch_order_id "
                + "where d.tenant_id=? and d.workspace_id=? and d.id=? and i.incident_type='TEMPERATURE_EXCURSION' "
                + "and i.severity='CRITICAL' "
                + "union all select 1 from logistics.delivery_execution_hold h where h.tenant_id=? and h.workspace_id=? "
                + "and h.delivery_id=? and coalesce((select x.disposition from logistics.delivery_execution_disposition x "
                + "where x.tenant_id=h.tenant_id and x.workspace_id=h.workspace_id and x.hold_id=h.id "
                + "order by x.sequence desc limit 1),'CONTINUE_HOLD')<>'RELEASE')",
                Boolean.class,tenant,workspace,delivery,tenant,workspace,delivery,tenant,workspace,delivery));
    }
}
