package com.nexa.api.iam.infrastructure;

import com.nexa.api.shared.application.changefeed.ChangeEventAudience;
import com.nexa.api.shared.application.changefeed.ChangeEventView;
import com.nexa.api.shared.application.changefeed.ChangeFeedQueryPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class MultiRoleChangeFeedIT extends NexaWorkflowIntegrationSupport {
    @Autowired ChangeEventPersistencePort events;
    @Autowired ChangeFeedQueryPort feed;

    @BeforeEach
    void ensureSeedOwnerHasBothRoles() {
        restoreSeedOwnerRoles();
    }

    @AfterEach
    void restoreSeedOwnerRoles() {
        jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?",
                java.util.UUID.fromString(membershipId(OWNER_EMAIL)));
        String membershipId = membershipId(OWNER_EMAIL);
        jdbc.update("delete from tenant_management.membership_role_assignment where membership_id=?", java.util.UUID.fromString(membershipId));
        jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) "
                + "select m.id,w.tenant_id,m.workspace_id,?,current_timestamp from tenant_management.workspace_membership m "
                + "join tenant_management.workspace w on w.id=m.workspace_id where m.id=?", "TENANT_ADMIN", java.util.UUID.fromString(membershipId));
        jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) "
                + "select m.id,w.tenant_id,m.workspace_id,?,current_timestamp from tenant_management.workspace_membership m "
                + "join tenant_management.workspace w on w.id=m.workspace_id where m.id=?", "COMPANY_OWNER", java.util.UUID.fromString(membershipId));
    }

    @Test
    void organizationMembershipRepresentationKeepsAllRoles() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        var result = mockMvc.perform(get("/api/v1/workspace-memberships").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var memberships = json(result);
        assertThat(memberships.toString()).contains("TENANT_ADMIN", "COMPANY_OWNER");
        assertThat(memberships.toString()).doesNotContain("\"role\"");
    }

    @Test
    void multiRoleAudienceIsUnionedFilteredInSqlAndRevalidatedForANewStream() throws Exception {
        String initialToken = accessToken(OWNER_EMAIL, "PLATFORM");
        var initialSession = mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + initialToken))
                .andExpect(status().isOk()).andReturn();
        var initialRoles = json(initialSession).at("/membership/roles");
        assertThat(initialRoles.toString()).contains("TENANT_ADMIN", "COMPANY_OWNER");
        assertThat(jdbc.query("select r.role from tenant_management.membership_role_assignment r "
                        + "where r.membership_id=? order by r.role", (rs, row) -> rs.getString(1),
                java.util.UUID.fromString(membershipId(OWNER_EMAIL))))
                .containsExactly("COMPANY_OWNER", "TENANT_ADMIN");

        String tenant = tenantId();
        String workspace = workspaceId();
        long cursor = jdbc.queryForObject("select coalesce(max(\"sequence\"),0) from integration.change_event where tenant_id=? and workspace_id=?",
                Long.class, java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace));
        String ownerAggregate = uuid();
        String salesAggregate = uuid();
        events.append(tenant, workspace, null, "membership", ownerAggregate, "organization.membership.role-changed", "ACTIVE",
                System.currentTimeMillis(), false);
        events.append(tenant, workspace, null, "purchase_request", salesAggregate, "sales.purchase-request.created", "CREATED",
                System.currentTimeMillis(), false);

        assertThat(jdbc.queryForObject("select audiences::text from integration.change_event where tenant_id=? and workspace_id=? and aggregate_id=?",
                String.class, java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(ownerAggregate)))
                .isEqualTo("{OWNER}");
        assertThat(jdbc.queryForObject("select audiences::text from integration.change_event where tenant_id=? and workspace_id=? and aggregate_id=?",
                String.class, java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace), java.util.UUID.fromString(salesAggregate)))
                .isEqualTo("{SALES}");

        var ownerOnly = feed.after(tenant, workspace, null, Set.of(ChangeEventAudience.OWNER), cursor, 100);
        assertThat(ownerOnly).extracting(ChangeEventView::eventType)
                .containsExactly("organization.membership.role-changed");

        var union = feed.after(tenant, workspace, null, Set.of(ChangeEventAudience.OWNER, ChangeEventAudience.SALES), cursor, 100);
        assertThat(union).extracting(ChangeEventView::eventType)
                .containsExactly("organization.membership.role-changed", "sales.purchase-request.created");
        assertThat(union.stream().filter(event -> event.eventType().equals("organization.membership.role-changed")).count()).isEqualTo(1);
        assertThat(feed.after(tenant, uuid(), null, Set.of(ChangeEventAudience.OWNER, ChangeEventAudience.SALES), cursor, 100))
                .isEmpty();

        String initialStream = streamBody(initialToken, cursor);
        long scopeMinimum = feed.minimumId(tenant, workspace, null);
        long scopeMaximum = jdbc.queryForObject("select coalesce(max(\"sequence\"),0) from integration.change_event where tenant_id=? and workspace_id=?",
                Long.class, java.util.UUID.fromString(tenant), java.util.UUID.fromString(workspace));
        assertThat(occurrences(initialStream, "organization.membership.role-changed"))
                .withFailMessage("Change-feed replay mismatch: cursor=%d, scopeMinimum=%d, scopeMaximum=%d, stream=%s",
                        cursor, scopeMinimum, scopeMaximum, initialStream)
                .isEqualTo(1);
        assertThat(initialStream).doesNotContain("sales.purchase-request.created");

        String ownerMembership = membershipId(OWNER_EMAIL);
        MvcResult currentMembership = mockMvc.perform(get("/api/v1/workspace-memberships/" + ownerMembership)
                        .header("Authorization", "Bearer " + initialToken))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(patch("/api/v1/workspace-memberships/" + ownerMembership + "/roles")
                        .header("Authorization", "Bearer " + initialToken)
                        .header("If-Match", currentMembership.getResponse().getHeader("ETag"))
                        .header("X-Correlation-ID", "change-feed-role-removal-" + uuid())
                        .contentType("application/json")
                        .content("{\"roles\":[\"TENANT_ADMIN\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + initialToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/change-feed/stream").header("Authorization", "Bearer " + initialToken))
                .andExpect(status().isForbidden());

        String newToken = accessToken(OWNER_EMAIL, "PLATFORM");
        var newSession = mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk()).andReturn();
        var newRoles = json(newSession).at("/membership/roles");
        assertThat(newRoles.size()).isEqualTo(1);
        assertThat(newRoles.get(0).asText()).isEqualTo("TENANT_ADMIN");

        String newStream = streamBody(newToken, cursor);
        assertThat(occurrences(newStream, "organization.membership.role-changed")).isEqualTo(1);
        assertThat(newStream).doesNotContain("sales.purchase-request.created");

        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header("Authorization", "Bearer " + newToken)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Nexa-Surface", "PLATFORM"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/change-feed/stream").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isUnauthorized());
    }

    private String streamBody(String token, long cursor) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/change-feed/stream")
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", Long.toString(cursor)))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            String body = result.getResponse().getContentAsString();
            for (int attempt = 0; body.isBlank() && attempt < 100; attempt++) {
                Thread.sleep(10);
                body = result.getResponse().getContentAsString();
            }
            return body;
        } finally {
            if (result.getRequest().isAsyncStarted()) result.getRequest().getAsyncContext().complete();
        }
    }

    private static long occurrences(String text, String value) {
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }
}
