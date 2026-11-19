package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingRequestRunner;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantBuyerDeliveryTrackingQueryFactory;
import com.nexa.api.salescommitment.application.model.SalesCommitmentQueryRunner;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the cloud read composition with a real, physically separate Tenant fixture database. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = "nexa.tenant-business.fulfillment-delivery.enabled=false")
@Import(CloudBuyerJourneyHttpIT.ReadComposition.class)
class CloudBuyerJourneyHttpIT extends PostgresIntegrationSupport {
    private static final String DELIVERIES = "/api/v1/buyer/deliveries";

    @Test
    void buyerReadsOnlyOwnTenantDeliveriesAndWriteRoutesRemainUnavailable() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID actor = UUID.fromString(membershipId(LOGISTICS_EMAIL));
        UUID account = tenantJdbc().queryForObject(
                "select id from sales.client_account where tenant_id=? and workspace_id=? and code='CLI-001'",
                UUID.class, tenant, workspace);
        UUID otherAccount = seedAccount(tenant, workspace);
        UUID ownDelivery = seedDelivery(tenant, workspace, actor, account);
        UUID otherDelivery = seedDelivery(tenant, workspace, actor, otherAccount);
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String logistics = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");

        mockMvc.perform(get(DELIVERIES).param("size", "100").param("clientAccountId", otherAccount.toString())
                        .header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(ownDelivery.toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(otherDelivery.toString()))));
        mockMvc.perform(get(DELIVERIES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(DELIVERIES).header("Authorization", logistics)).andExpect(status().isForbidden());

        mockMvc.perform(get(DELIVERIES + "/{id}", ownDelivery).header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(ownDelivery.toString()))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.salesOrderStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.clientAccountId").doesNotExist())
                .andExpect(jsonPath("$.driverMembershipId").doesNotExist());
        mockMvc.perform(get(DELIVERIES + "/{id}/events", ownDelivery).header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("HANDED_OVER"))
                .andExpect(jsonPath("$[1].type").value("IN_TRANSIT"))
                .andExpect(jsonPath("$[1].actorMembershipId").doesNotExist())
                .andExpect(jsonPath("$[1].reason").doesNotExist());
        mockMvc.perform(get(DELIVERIES + "/{id}/buyer-receipts", ownDelivery).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        for (String suffix : new String[]{"", "/events", "/buyer-receipts"}) {
            mockMvc.perform(get(DELIVERIES + "/{id}" + suffix, otherDelivery).header("Authorization", buyer))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get(DELIVERIES + "/{id}/live-location", ownDelivery).header("Authorization", buyer))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/v1/driver/workdays").header("Authorization", logistics)
                        .header("Idempotency-Key", uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationAvailable\":false}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void buyerListsOwnRequestsAndOrdersAndCannotReadOtherAccountsById() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID buyerMembership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID account = tenantJdbc().queryForObject(
                "select id from sales.client_account where tenant_id=? and workspace_id=? and code='CLI-001'",
                UUID.class, tenant, workspace);
        UUID otherAccount = seedAccount(tenant, workspace);
        UUID ownRequest = seedPurchaseRequest(tenant, workspace, buyerMembership, account);
        UUID otherRequest = seedPurchaseRequest(tenant, workspace, buyerMembership, otherAccount);
        UUID ownOrder = seedOrder(tenant, workspace, buyerMembership, account);
        UUID otherOrder = seedOrder(tenant, workspace, buyerMembership, otherAccount);
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");

        assertBuyerReadsOnlyOwnRecords("/api/v1/purchase-requests", buyer, ownRequest, otherRequest);
        assertBuyerReadsOnlyOwnRecords("/api/v1/sales-orders", buyer, ownOrder, otherOrder);
        mockMvc.perform(get("/api/v1/sales-orders").param("clientAccountId", otherAccount.toString())
                        .header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    private void assertBuyerReadsOnlyOwnRecords(String route, String buyer, UUID own, UUID other) throws Exception {
        mockMvc.perform(get(route).header("Authorization", buyer).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(own.toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(other.toString()))));
        mockMvc.perform(get(route + "/{id}", own).header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(own.toString()));
        mockMvc.perform(get(route + "/{id}", other).header("Authorization", buyer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(route + "/{id}/events", own).header("Authorization", buyer))
                .andExpect(status().isOk());
        mockMvc.perform(get(route + "/{id}/events", other).header("Authorization", buyer))
                .andExpect(status().isNotFound());
    }

    @Test
    void salesQueriesUseTenantDataAndRestrictOrdersToAssignedAccounts() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID salesperson = UUID.fromString(membershipId(SALES_EMAIL));
        UUID buyer = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID assigned = seedAccount(tenant, workspace);
        UUID unassigned = seedAccount(tenant, workspace);
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=? where id=?",
                salesperson, assigned);
        UUID request = seedPurchaseRequest(tenant, workspace, buyer, assigned);
        UUID visible = seedOrder(tenant, workspace, salesperson, assigned);
        UUID hidden = seedOrder(tenant, workspace, salesperson, unassigned);
        String token = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/purchase-requests/{id}", request).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(request.toString()));
        mockMvc.perform(get("/api/v1/sales-orders").param("size", "100").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(visible.toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(hidden.toString()))));
        mockMvc.perform(get("/api/v1/sales-orders/{id}", visible).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sales-orders/{id}", hidden).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    private UUID seedPurchaseRequest(UUID tenant, UUID workspace, UUID buyer, UUID account) {
        UUID request = UUID.randomUUID();
        tenantJdbc().update("""
                insert into sales.purchase_request
                    (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at)
                values (?,?,?,?,?,?,'DRAFT','NORMAL',current_timestamp,current_timestamp)
                """, request, tenant, workspace, account, buyer, "BUYER-" + request.toString().substring(0, 8));
        return request;
    }

    private UUID seedAccount(UUID tenant, UUID workspace) {
        UUID account = UUID.randomUUID();
        String suffix = account.toString().substring(0, 8);
        String taxNumber = "20" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(account.getMostSignificantBits(), 1_000_000_000L));
        tenantJdbc().update("""
                insert into sales.client_account
                    (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,
                     tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,
                     status,created_at,updated_at)
                values (?,?,?,?,'Other account','Other account','PE','RUC',?,'RETAIL','Integration fixture',
                        ?,'+51000000000','STANDARD','IMMEDIATE','ACTIVE',current_timestamp,current_timestamp)
                """, account, tenant, workspace, "TRACK-" + suffix, taxNumber, "tracking-" + suffix + "@example.test");
        return account;
    }

    private UUID seedOrder(UUID tenant, UUID workspace, UUID actor, UUID account) {
        UUID order = UUID.randomUUID();
        tenantJdbc().update("""
                insert into sales.sales_order
                    (id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,priority,currency,
                     total_amount,status,created_at,updated_at,order_source,origin_type)
                values (?,?,?,?,?,?,'NORMAL','PEN',2,'CONFIRMED',current_timestamp,current_timestamp,'MANUAL','MANUAL')
                """, order, tenant, workspace, "T" + order.toString().substring(0, 8), account, actor);
        return order;
    }

    private UUID seedDelivery(UUID tenant, UUID workspace, UUID actor, UUID account) {
        UUID order = seedOrder(tenant, workspace, actor, account);
        UUID fulfillment = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        var business = tenantJdbc();
        business.update("""
                insert into logistics.fulfillment
                    (id,tenant_id,workspace_id,sales_order_id,physical_allocation_id,status,planned_at,created_at,
                     updated_at,actor_membership_id)
                values (?,?,?,?,?,'HANDED_OVER',current_timestamp,current_timestamp,current_timestamp,?)
                """, fulfillment, tenant, workspace, order, UUID.randomUUID(), actor);
        business.update("""
                insert into logistics.delivery
                    (id,tenant_id,workspace_id,fulfillment_id,status,destination_snapshot,dispatched_at,
                     created_at,updated_at)
                values (?,?,?,?,'IN_TRANSIT','Fixture destination',current_timestamp,current_timestamp,current_timestamp)
                """, delivery, tenant, workspace, fulfillment);
        business.update("""
                insert into logistics.fulfillment_event
                    (id,tenant_id,workspace_id,fulfillment_id,event_type,to_status,actor_membership_id,occurred_at)
                values (?,?,?,?,'HAND_OVER','HANDED_OVER',?,current_timestamp - interval '1 minute')
                """, UUID.randomUUID(), tenant, workspace, fulfillment, actor);
        business.update("""
                insert into logistics.delivery_event
                    (id,tenant_id,workspace_id,delivery_id,event_type,actor_membership_id,reason,occurred_at)
                values (?,?,?,?,'TRANSIT_START',?,'Internal fixture reason',current_timestamp)
                """, UUID.randomUUID(), tenant, workspace, delivery, actor);
        return delivery;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadComposition {
        @Bean
        @Primary
        BuyerDeliveryTrackingRequestRunner cloudBuyerDeliveryTrackingReadsForHttpTest(
                TenantBusinessDatabaseRouter router, TenantBuyerDeliveryTrackingQueryFactory tracking,
                TenantCustomerAccountQueryFactory accounts, TenantSalesOrderFulfillmentQueryFactory orders) {
            return new BuyerDeliveryTrackingReadConfiguration.CloudReads()
                    .cloudBuyerDeliveryTrackingReads(router, tracking, accounts, orders);
        }
        @Bean
        @Primary
        SalesCommitmentQueryRunner cloudBuyerSalesReadsForHttpTest(TenantBusinessDatabaseRouter router,
                TenantSalesCommitmentCompositionProvider provider, PurchaseRequestUseCase purchaseRequests,
                SalesOrderUseCase salesOrders) {
            var beans = new StaticListableBeanFactory();
            beans.addBean("cloudSalesReads",
                    new SalesCommitmentQueryConfiguration.CloudReads().cloudSalesReads(router, provider, true, true));
            return new SalesCommitmentQueryConfiguration().salesCommitmentQueries(purchaseRequests, salesOrders,
                    beans.getBeanProvider(SalesCommitmentQueryConfiguration.CloudSalesReads.class));
        }
    }
}
