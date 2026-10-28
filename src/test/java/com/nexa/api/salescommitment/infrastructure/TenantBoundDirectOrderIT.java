package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.bootstrap.local.LocalTenantBusinessFixtureSeeder;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBoundDirectOrderIT extends NexaWorkflowIntegrationSupport {

    @Autowired
    private LocalTenantBusinessFixtureSeeder tenantFixtureSeeder;

    private final java.util.List<UUID> fixtureTenants = new java.util.ArrayList<>();

    @AfterEach
    void retireAdditionalMemberships() {
        for (UUID tenant : fixtureTenants) {
            jdbc.update("update tenant_management.workspace_membership set status='DISABLED', "
                    + "updated_at=current_timestamp, version=version+1 where tenant_id=? and status='ACTIVE'", tenant);
        }
    }

    @Test
    void directOrdersUseTheirPhysicalTenantDatabaseAndMissingBindingFailsClosed() throws Exception {
        ensureCommercialInventory();

        UUID tenantA = UUID.fromString(tenantId());
        UUID workspaceA = UUID.fromString(workspaceId());
        UUID salesMembershipA = UUID.fromString(membershipId(SALES_EMAIL));
        JdbcTemplate tenantDatabaseA = tenantJdbc();
        String databaseA = tenantDatabaseA.queryForObject("select current_database()", String.class);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        TenantScope tenantB = createTenantScope("direct-b-" + suffix,
                List.of(OWNER_EMAIL, SALES_EMAIL, WAREHOUSE_EMAIL, BUYER_EMAIL));
        provisionTenantBusinessDatabase(tenantB.tenantId(), tenantB.workspaceId());
        seedTenantCatalogFixture(tenantB.tenantId(), tenantB.workspaceId());
        JdbcTemplate tenantDatabaseB = tenantJdbcFor(tenantB.tenantId());
        tenantFixtureSeeder.seed(tenantDatabaseB, tenantB.tenantId(), tenantB.workspaceId(),
                tenantB.memberships().get(BUYER_EMAIL), "CLI-001");
        seedCommercialInventory(tenantB, suffix);
        String databaseB = tenantDatabaseB.queryForObject("select current_database()", String.class);
        assertThat(databaseB).isNotEqualTo(databaseA);

        String idempotencyKey = "direct-tenant-isolation-" + suffix;
        String accountA = buyerClientAccountId();
        String accountB = buyerClientAccountId(tenantDatabaseB, tenantB,
                tenantB.memberships().get(BUYER_EMAIL));
        MvcResult orderAResult = postDirectOrder(accessToken(SALES_EMAIL, "PLATFORM"), idempotencyKey,
                directOrderBody(accountA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.originType").value("DIRECT_ORDER"))
                .andReturn();
        MvcResult orderBResult = postDirectOrder(accessTokenForWorkspace(SALES_EMAIL, "PLATFORM", tenantB.slug()),
                idempotencyKey, directOrderBody(accountB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.originType").value("DIRECT_ORDER"))
                .andReturn();

        UUID orderA = UUID.fromString(json(orderAResult).path("id").asText());
        UUID orderB = UUID.fromString(json(orderBResult).path("id").asText());
        assertDirectOrderEffects(tenantDatabaseA, tenantA, workspaceA, orderA, salesMembershipA, idempotencyKey);
        assertDirectOrderEffects(tenantDatabaseB, tenantB.tenantId(), tenantB.workspaceId(), orderB,
                tenantB.memberships().get(SALES_EMAIL), idempotencyKey);
        assertThat(orderCount(tenantDatabaseA, orderB)).isZero();
        assertThat(orderCount(tenantDatabaseB, orderA)).isZero();
        assertThat(idempotencyCount(tenantDatabaseA, tenantB.memberships().get(SALES_EMAIL), idempotencyKey)).isZero();
        assertThat(idempotencyCount(tenantDatabaseB, salesMembershipA, idempotencyKey)).isZero();

        TenantScope unboundTenant = createTenantScope("direct-unbound-" + suffix, List.of(SALES_EMAIL));
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.tenant_business_database_binding where tenant_id=?",
                Integer.class, unboundTenant.tenantId())).isZero();
        String missingBindingKey = "direct-missing-binding-" + suffix;
        int ordersABefore = directOrderCount(tenantDatabaseA, tenantA, workspaceA);
        int ordersBBefore = directOrderCount(tenantDatabaseB, tenantB.tenantId(), tenantB.workspaceId());
        postDirectOrder(accessTokenForWorkspace(SALES_EMAIL, "PLATFORM", unboundTenant.slug()), missingBindingKey,
                directOrderBody(accountA))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TECHNICAL_CAPABILITY_UNAVAILABLE"));

        assertThat(directOrderCount(tenantDatabaseA, tenantA, workspaceA)).isEqualTo(ordersABefore);
        assertThat(directOrderCount(tenantDatabaseB, tenantB.tenantId(), tenantB.workspaceId())).isEqualTo(ordersBBefore);
        assertThat(idempotencyCount(tenantDatabaseA, salesMembershipA, missingBindingKey)).isZero();
        assertThat(idempotencyCount(tenantDatabaseB, tenantB.memberships().get(SALES_EMAIL), missingBindingKey)).isZero();
    }

    private void seedCommercialInventory(TenantScope scope, String suffix) throws Exception {
        String warehouseToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, "PLATFORM", scope.slug());
        String ownerToken = accessTokenForWorkspace(OWNER_EMAIL, "PLATFORM", scope.slug());
        String warehouseJson = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-DIRECT-" + suffix + "\",\"name\":\"Direct order warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String warehouseId = tools.jackson.databind.json.JsonMapper.shared().readTree(warehouseJson).path("id").asText();
        mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/access-grants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + scope.memberships().get(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());

        warehouseToken = accessTokenForWorkspace(WAREHOUSE_EMAIL, "PLATFORM", scope.slug());
        String zoneJson = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-DIRECT-" + suffix + "\",\"name\":\"Direct order zone\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String zoneId = tools.jackson.databind.json.JsonMapper.shared().readTree(zoneJson).path("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("Idempotency-Key", "direct-inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"CAT-0002\",\"batchNumber\":\"B-DIRECT-" + suffix
                                + "\",\"expirationDate\":\"2099-12-31\",\"quantity\":100,\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
    }

    private TenantScope createTenantScope(String slugPrefix, List<String> emails) {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        fixtureTenants.add(tenantId);
        String suffix = tenantId.toString().replace("-", "").substring(0, 10);
        String tenantSlug = slugPrefix + "-tenant-" + suffix;
        String workspaceSlug = slugPrefix + "-workspace-" + suffix;
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenantId, "Direct order " + slugPrefix, tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspaceId, tenantId, "Direct order workspace " + slugPrefix, workspaceSlug);

        Map<String, UUID> memberships = new LinkedHashMap<>();
        for (String email : emails) {
            UUID sourceMembership = UUID.fromString(membershipId(email));
            UUID targetMembership = UUID.randomUUID();
            jdbc.update("insert into tenant_management.workspace_membership "
                            + "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                            + "select ?,?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                            + "from tenant_management.workspace_membership where id=?",
                    targetMembership, tenantId, workspaceId, sourceMembership);
            jdbc.update("insert into tenant_management.membership_role_definition "
                            + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                            + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition "
                            + "where membership_id=?",
                    targetMembership, tenantId, workspaceId, sourceMembership);
            memberships.put(email, targetMembership);
        }
        return new TenantScope(tenantId, workspaceId, workspaceSlug, memberships);
    }

    private String buyerClientAccountId(JdbcTemplate tenantDatabase, TenantScope scope, UUID buyerMembership) {
        return tenantDatabase.queryForObject("select client_account_id::text from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and workspace_membership_id=?",
                String.class, scope.tenantId(), scope.workspaceId(), buyerMembership);
    }

    private void assertDirectOrderEffects(JdbcTemplate tenantDatabase, UUID tenantId, UUID workspaceId, UUID orderId,
            UUID actorMembership, String idempotencyKey) {
        String commitmentId = tenantDatabase.queryForObject("select commercial_commitment_id::text from sales.sales_order "
                        + "where tenant_id=? and workspace_id=? and id=? and order_source='DIRECT_ORDER' and status='CONFIRMED'",
                String.class, tenantId, workspaceId, orderId);
        assertThat(tenantDatabase.queryForObject("select status from sales.commercial_commitment where tenant_id=? "
                        + "and workspace_id=? and id=?", String.class, tenantId, workspaceId, UUID.fromString(commitmentId)))
                .isEqualTo("CONVERTED");
        assertThat(tenantDatabase.queryForObject("select count(*) from warehouse.inventory_backing "
                        + "where tenant_id=? and workspace_id=? and commercial_commitment_id=? and status='BACKED'",
                Integer.class, tenantId, workspaceId, UUID.fromString(commitmentId))).isEqualTo(1);
        assertThat(tenantDatabase.queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and event_type='SALES_ORDER_CONFIRMED' and aggregate_id=?",
                Integer.class, tenantId, workspaceId, orderId)).isEqualTo(1);
        assertThat(idempotencyCount(tenantDatabase, actorMembership, idempotencyKey)).isEqualTo(1);
    }

    private int directOrderCount(JdbcTemplate tenantDatabase, UUID tenantId, UUID workspaceId) {
        return tenantDatabase.queryForObject("select count(*) from sales.sales_order where tenant_id=? and workspace_id=? "
                + "and order_source='DIRECT_ORDER'", Integer.class, tenantId, workspaceId);
    }

    private int orderCount(JdbcTemplate tenantDatabase, UUID orderId) {
        return tenantDatabase.queryForObject("select count(*) from sales.sales_order where id=?", Integer.class, orderId);
    }

    private int idempotencyCount(JdbcTemplate tenantDatabase, UUID actorMembership, String idempotencyKey) {
        return tenantDatabase.queryForObject("select count(*) from sales.idempotency_record where operation='direct-order' "
                + "and actor_membership_id=? and idempotency_key=?", Integer.class, actorMembership, idempotencyKey);
    }

    private ResultActions postDirectOrder(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/direct-orders")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String directOrderBody(String clientAccountId) {
        return "{\"clientAccountId\":\"" + clientAccountId
                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\","
                + "\"deliveryProfileSnapshot\":\"Direct order delivery\",\"paymentOption\":\"IMMEDIATE\","
                + "\"comment\":\"tenant isolation integration\",\"lines\":[{\"catalogItemId\":\"CAT-0002\","
                + "\"quantity\":2,\"unit\":\"UNIT\"}]}";
    }

    private record TenantScope(UUID tenantId, UUID workspaceId, String slug, Map<String, UUID> memberships) { }
}
