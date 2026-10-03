package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery.IncidentSubject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Fulfillment-owned delivery, POD, incident, and temperature facts for documents. */
@Repository
@Profile("!test")
public class JdbcFulfillmentDocumentSourceQuery implements FulfillmentDocumentSourceQuery {
    private final JdbcTemplate jdbc;

    public JdbcFulfillmentDocumentSourceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Dispatch> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchId) {
        return jdbc.query("select id,client_account_id,dispatch_number,status,destination_snapshot,delivery_window_start,eta,"
                        + "responsible_display_name_snapshot,vehicle_reference,route_name,temperature_status,sales_order_id "
                        + "from logistics.dispatch_order where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new Dispatch(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getString("dispatch_number"),
                        rs.getString("status"), rs.getString("destination_snapshot"), instant(rs, "delivery_window_start"),
                        instant(rs, "eta"), rs.getString("responsible_display_name_snapshot"),
                        rs.getString("vehicle_reference"), rs.getString("route_name"),
                        rs.getString("temperature_status"), rs.getObject("sales_order_id", UUID.class),
                        temperatureSummary(tenantId, workspaceId, rs.getObject("id", UUID.class))),
                tenantId, workspaceId, dispatchId).stream().findFirst();
    }

    @Override
    public Optional<ProofOfDelivery> findPod(UUID tenantId, UUID workspaceId, UUID proofOfDeliveryId) {
        return jdbc.query("select p.id,d.client_account_id,p.receiver_name,p.completed_at,p.notes,p.status,"
                        + "p.photo_evidence_declared,p.signature_evidence_declared,d.id dispatch_id "
                        + "from logistics.proof_of_delivery p join logistics.dispatch_order d "
                        + "on d.tenant_id=p.tenant_id and d.workspace_id=p.workspace_id and d.id=p.dispatch_order_id "
                        + "where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> new ProofOfDelivery(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getString("receiver_name"),
                        rs.getTimestamp("completed_at").toInstant(), rs.getString("notes"), rs.getString("status"),
                        rs.getBoolean("photo_evidence_declared"), rs.getBoolean("signature_evidence_declared"),
                        rs.getObject("dispatch_id", UUID.class)), tenantId, workspaceId, proofOfDeliveryId)
                .stream().findFirst();
    }

    @Override
    public Optional<Incident> findIncident(UUID tenantId, UUID workspaceId, UUID incidentId) {
        return jdbc.query("select i.id,d.client_account_id,i.incident_type,i.severity,i.description,i.occurred_at,"
                        + "i.resolution,d.id dispatch_id from logistics.delivery_incident i join logistics.dispatch_order d "
                        + "on d.tenant_id=i.tenant_id and d.workspace_id=i.workspace_id and d.id=i.dispatch_order_id "
                        + "where i.tenant_id=? and i.workspace_id=? and i.id=?",
                (rs, row) -> new Incident(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getString("incident_type"),
                        rs.getString("severity"), rs.getString("description"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("resolution"),
                        rs.getObject("dispatch_id", UUID.class)), tenantId, workspaceId, incidentId)
                .stream().findFirst();
    }

    @Override
    public Optional<IncidentSubject> findIncidentSubject(UUID tenantId, UUID workspaceId, UUID incidentId) {
        return jdbc.query("select i.id,dispatch.client_account_id customer_account_id,fulfillment.sales_order_id,"
                        + "'RECORDED' status from logistics.driver_delivery_incident i "
                        + "join logistics.delivery delivery on delivery.tenant_id=i.tenant_id "
                        + "and delivery.workspace_id=i.workspace_id and delivery.id=i.delivery_id "
                        + "left join logistics.dispatch_order dispatch on dispatch.tenant_id=delivery.tenant_id "
                        + "and dispatch.workspace_id=delivery.workspace_id and dispatch.id=delivery.dispatch_order_id "
                        + "left join logistics.fulfillment fulfillment on fulfillment.tenant_id=delivery.tenant_id "
                        + "and fulfillment.workspace_id=delivery.workspace_id and fulfillment.id=delivery.fulfillment_id "
                        + "where i.tenant_id=? and i.workspace_id=? and i.id=?",
                (rs, row) -> new IncidentSubject(rs.getObject("id", UUID.class),
                        rs.getObject("customer_account_id", UUID.class), rs.getString("status"),
                        rs.getObject("sales_order_id", UUID.class)),
                tenantId, workspaceId, incidentId).stream().findFirst();
    }

    private String temperatureSummary(UUID tenantId, UUID workspaceId, UUID dispatchId) {
        return jdbc.queryForObject("select coalesce(string_agg(coalesce(value::text,'n/a') || ' ' || unit, ', ' order by recorded_at desc), 'No temperature readings') "
                + "from logistics.temperature_reading where tenant_id=? and workspace_id=? and dispatch_order_id=?",
                String.class, tenantId, workspaceId, dispatchId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }
}
