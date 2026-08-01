package com.nexa.api.shared.infrastructure;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ChangeFeedAudienceIsolationIT extends PostgresIntegrationSupport {
    @Autowired ChangeEventPersistencePort events;

    @Test void explicitBuyerEventIsScopedToBuyerAndInternalEventIsNot() {
        String tenant = tenantId(); String workspace = workspaceId(); String client = buyerClientAccountId(); String aggregate = uuid();
        events.append(tenant, workspace, client, "dispatch_order", aggregate, "logistics.dispatch.scheduled", "DELIVERY_SCHEDULED", System.currentTimeMillis(), true);
        events.append(tenant, workspace, client, "dispatch_order", uuid(), "logistics.dispatch.assigned", "ASSIGNED", System.currentTimeMillis(), false);
        var audiences = jdbc.query("select event_type,audiences from integration.change_event where tenant_id=? and workspace_id=? and client_account_id=? order by sequence desc limit 2", (rs, row) -> rs.getString(1) + ":" + java.util.Arrays.toString((String[]) rs.getArray(2).getArray()), java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(client));
        assertThat(audiences).anyMatch(value -> value.startsWith("logistics.dispatch.scheduled:") && value.contains("BUYER"));
        assertThat(audiences).anyMatch(value -> value.startsWith("logistics.dispatch.assigned:") && !value.contains("BUYER"));
    }
}
