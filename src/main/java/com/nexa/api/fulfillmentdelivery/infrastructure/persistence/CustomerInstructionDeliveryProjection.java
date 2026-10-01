package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Copies frozen Customer instructions when an operational Delivery is materialized. */
public final class CustomerInstructionDeliveryProjection {
    private CustomerInstructionDeliveryProjection() { }
    public static void copy(JdbcTemplate jdbc, UUID tenant, UUID workspace, UUID order, UUID delivery, Instant now) {
        var rows = jdbc.query("select distinct on (instruction_id) * from logistics.customer_instruction_revision "
                        + "where tenant_id=? and workspace_id=? and sales_order_id=? order by instruction_id,instruction_version desc",
                (rs, n) -> new Revision(rs.getObject("instruction_id",UUID.class),rs.getLong("instruction_version"),
                        rs.getString("kind"),rs.getString("content"),rs.getObject("recorded_by_membership_id",UUID.class),
                        rs.getTimestamp("recorded_at"),rs.getString("request_hash"),rs.getString("source_kind"),rs.getString("source_reference"),rs.getObject("revision_id",UUID.class)),
                tenant,workspace,order);
        var versions = jdbc.query("select version,instruction_set_version from logistics.delivery "
                        + "where tenant_id=? and workspace_id=? and id=? for update",
                (rs,n) -> new long[]{rs.getLong(1),rs.getLong(2)},tenant,workspace,delivery);
        if (versions.isEmpty()) throw new IllegalStateException("Delivery projection requires an owned Delivery");
        long nextVersion = versions.getFirst()[0] + 1;
        long nextSetVersion = versions.getFirst()[1] + 1;
        int inserted = 0;
        for (var row : rows) {
            inserted += jdbc.update("insert into logistics.delivery_instruction_revision(revision_id,tenant_id,workspace_id,delivery_id,instruction_id,"
                            + "instruction_version,kind,content,authored_by_membership_id,authored_at,request_hash,published_delivery_version,"
                            + "instruction_set_version,source_kind,source_reference,source_revision_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
                    UUID.randomUUID(),tenant,workspace,delivery,UUID.nameUUIDFromBytes((delivery+"|"+row.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),row.version(),row.kind(),row.content(),row.actor(),
                    row.at(),row.hash(),nextVersion,nextSetVersion,row.source(),row.reference(),row.revisionId());
        }
        if (inserted > 0) jdbc.update("update logistics.delivery set version=version+1,instruction_set_version=instruction_set_version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=?",Timestamp.from(now),tenant,workspace,delivery);
    }
    private record Revision(UUID id,long version,String kind,String content,UUID actor,Timestamp at,
                            String hash,String source,String reference,UUID revisionId) { }
}
