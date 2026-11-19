package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.application.port.ObjectStoragePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.creditreceivables.application.publicapi.BuyerCreditExposureReader;
import com.nexa.api.creditreceivables.tenantdatabase.TenantBuyerCreditExposureReaderFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingRequestRunner;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantBuyerDeliveryTrackingQueryFactory;
import com.nexa.api.salescommitment.application.model.SalesCommitmentQueryRunner;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the cloud read composition with a real, physically separate Tenant fixture database. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.business-documents.enabled=false",
        "nexa.tenant-business.fulfillment-delivery.enabled=false",
        "nexa.tenant-business.purchase-request.enabled=false"
})
@Import(CloudBuyerJourneyHttpIT.ReadComposition.class)
class CloudBuyerJourneyHttpIT extends PostgresIntegrationSupport {
    private static final String DELIVERIES = "/api/v1/buyer/deliveries";

    @Autowired
    private ObjectStoragePort storage;

    @Test
    void buyerOpensOnlyOwnTenantDocumentsAndDownloadsTheStoredContent() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID ownAccount = tenantJdbc().queryForObject(
                "select id from sales.client_account where tenant_id=? and workspace_id=? and code='CLI-001'",
                UUID.class, tenant, workspace);
        UUID otherAccount = seedAccount(tenant, workspace);
        UUID actor = UUID.fromString(membershipId(SALES_EMAIL));
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=? where id=?", actor, ownAccount);
        UUID subject = seedOrder(tenant, workspace, actor, ownAccount);
        UUID otherSubject = seedOrder(tenant, workspace, actor, otherAccount);
        byte[] bytes = "%PDF-1.7\nBuyer document fixture".getBytes(StandardCharsets.US_ASCII);
        UUID ownDocument = seedIssuedDocument(tenant, workspace, ownAccount, subject, bytes);
        UUID otherDocument = seedIssuedDocument(tenant, workspace, otherAccount, otherSubject, bytes);
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String operations = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String documents = "/api/v1/business-documents";

        mockMvc.perform(get(documents).param("subjectType", "SALES_ORDER")
                        .param("subjectId", subject.toString()).param("size", "1").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ownDocument.toString()));
        mockMvc.perform(get(documents).param("subjectType", "SALES_ORDER")
                        .param("subjectId", otherSubject.toString()).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get(documents + "/{id}", ownDocument).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GENERATED"));
        mockMvc.perform(get(documents + "/{id}/events", ownDocument).header("Authorization", buyer))
                .andExpect(status().isOk());
        mockMvc.perform(get(documents + "/{id}/downloads", ownDocument).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().exists("X-Content-SHA256"));
        mockMvc.perform(get(documents + "/{id}", otherDocument).header("Authorization", buyer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(documents + "/{id}/downloads", otherDocument).header("Authorization", buyer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(documents)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(documents).header("Authorization", operations))
                .andExpect(status().isServiceUnavailable());
        String sales = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get(documents).param("subjectType", "SALES_ORDER")
                        .param("subjectId", subject.toString()).param("size", "1").header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ownDocument.toString()));
        mockMvc.perform(get(documents).param("subjectType", "SALES_ORDER")
                        .param("subjectId", otherSubject.toString()).header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get(documents + "/{id}", ownDocument).header("Authorization", sales))
                .andExpect(status().isOk());
        mockMvc.perform(get(documents + "/{id}/events", ownDocument).header("Authorization", sales))
                .andExpect(status().isOk());
        mockMvc.perform(get(documents + "/{id}/downloads", ownDocument).header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mockMvc.perform(get(documents + "/{id}", otherDocument).header("Authorization", sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(documents + "/{id}/events", otherDocument).header("Authorization", sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(documents + "/{id}/downloads", otherDocument).header("Authorization", sales))
                .andExpect(status().isNotFound());
    }

    private UUID seedIssuedDocument(UUID tenant, UUID workspace, UUID account, UUID subject, byte[] bytes) {
        UUID id = UUID.randomUUID();
        String object = "documents/" + tenant + "/" + id + ".pdf";
        var stored = storage.put(object, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
        tenantJdbc().update("""
                insert into business_documents.object_storage_object
                    (object_key,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at)
                values (?,?,?,?,?,true,current_timestamp)
                """, stored.objectKey(), "nexa-private", stored.checksumSha256(), stored.contentType(), stored.byteSize());
        tenantJdbc().update("""
                insert into business_documents.business_document
                    (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,
                     format,storage_object_key,checksum_sha256,content_type,byte_size,generated_at,created_at,updated_at)
                values (?,?,?,?,'SALES_ORDER',?,'ORDER_SUMMARY',1,'GENERATED','PDF',?,?,?,?,current_timestamp,
                        current_timestamp,current_timestamp)
                """, id, tenant, workspace, account, subject, stored.objectKey(), stored.checksumSha256(),
                stored.contentType(), stored.byteSize());
        return id;
    }

    @Test
    void walletReadsCurrentBuyerAndReportsOrderPaymentAvailability() throws Exception {
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String logistics = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/buyer/wallet").header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("PEN"))
                .andExpect(jsonPath("$.capabilities.orderPaymentSupported").value(false));
        mockMvc.perform(get("/api/v1/buyer/wallet").header("Authorization", logistics))
                .andExpect(status().isForbidden());
    }

    @Test
    void buyerReadsOnlyOwnTenantCreditAndWorkforceCannotUseTheBuyerRoute() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = tenantJdbc().queryForObject(
                "select id from sales.client_account where tenant_id=? and workspace_id=? and code='CLI-001'",
                UUID.class, tenant, workspace);
        UUID otherAccount = seedAccount(tenant, workspace);
        tenantJdbc().update("""
                insert into payments.credit_account
                    (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,status,version,created_at,updated_at)
                values (?,?,?,?,'USD',750,'ACTIVE',0,current_timestamp,current_timestamp),
                       (?,?,?,?,'USD',999,'ACTIVE',0,current_timestamp,current_timestamp)
                """, UUID.randomUUID(), tenant, workspace, account,
                UUID.randomUUID(), tenant, workspace, otherAccount);
        String route = "/api/v1/client-accounts/me/credit-exposure";
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String logistics = "Bearer " + accessToken(LOGISTICS_EMAIL, "PLATFORM");
        String sales = "Bearer " + accessToken(SALES_EMAIL, "PLATFORM");
        tenantJdbc().update("update sales.client_account set sales_owner_membership_id=? where id=?",
                UUID.fromString(membershipId(SALES_EMAIL)), account);

        mockMvc.perform(get("/api/v1/client-accounts/{id}/credit-exposure", account)
                        .param("currency", "USD").header("Authorization", sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.creditLimit").value(750));
        mockMvc.perform(get("/api/v1/client-accounts/{id}/credit-exposure", otherAccount)
                        .param("currency", "USD").header("Authorization", sales))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(route).param("currency", "USD").param("clientAccountId", otherAccount.toString())
                        .header("Authorization", buyer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.clientAccountId").value(account.toString()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.creditLimit").value(750))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get(route)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(route).header("Authorization", logistics)).andExpect(status().isForbidden());
    }

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

    @Test
    void buyerDownloadsExistingOwnOrderSummariesAndCannotExportAnotherAccountsOrder() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID buyerMembership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID account = tenantJdbc().queryForObject(
                "select id from sales.client_account where tenant_id=? and workspace_id=? and code='CLI-001'",
                UUID.class, tenant, workspace);
        UUID ownOrder = seedOrder(tenant, workspace, buyerMembership, account);
        UUID foreignOrder = seedOrder(tenant, workspace, buyerMembership, seedAccount(tenant, workspace));
        String buyer = "Bearer " + accessToken(BUYER_EMAIL, "PORTAL");
        String owner = "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM");
        String route = "/api/v1/buyer/orders/{id}/exports/summary";
        String number = "T" + ownOrder.toString().substring(0, 8);

        var pdf = mockMvc.perform(get(route + ".pdf", ownOrder).header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"nexa-order-summary-" + number + ".pdf\""))
                .andReturn().getResponse();
        try (var document = Loader.loadPDF(pdf.getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(document)).contains(ownOrder.toString(), number)
                    .doesNotContain(foreignOrder.toString());
        }

        var csv = mockMvc.perform(get(route + ".csv", ownOrder).header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"nexa-order-summary-" + number + ".csv\""))
                .andReturn().getResponse();
        assertThat(csv.getContentAsString(StandardCharsets.UTF_8)).contains(ownOrder.toString(), number)
                .doesNotContain(foreignOrder.toString());

        for (String format : new String[]{".pdf", ".csv"}) {
            mockMvc.perform(get(route + format, foreignOrder).header(HttpHeaders.AUTHORIZATION, buyer))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get(route + ".pdf", ownOrder)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(route + ".pdf", ownOrder).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/sales-orders/{id}/exports/summary.csv", ownOrder)
                        .header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isServiceUnavailable());
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
        TenantBoundSalesOrderSummaryExportConfiguration.CloudBuyerExports cloudBuyerExportsForHttpTest(
                TenantBusinessDatabaseRouter router, TenantCustomerAccountQueryFactory accounts,
                TenantSalesOrderSummaryExportFactory exports) {
            return new TenantBoundSalesOrderSummaryExportConfiguration().cloudBuyerExports(router, accounts, exports);
        }

        @Bean
        CentralTenantUseCaseFallbackGuardConfiguration.CloudBuyerBusinessDocuments cloudDocumentsForHttpTest(
                TenantBusinessDatabaseRouter router, TenantBusinessDocumentBindingsFactory bindings,
                WarehouseObjectAccess warehouses) {
            return new CentralTenantUseCaseFallbackGuardConfiguration.CloudBuyerBusinessDocumentConfiguration()
                    .cloudBuyerBusinessDocuments(router, bindings, warehouses, true, true);
        }

        @Bean
        @Primary
        BuyerWalletReadPort cloudWalletReadsForHttpTest(TenantBusinessDatabaseRouter router,
                TenantCustomerAccountQueryFactory accounts, BuyerWalletTenantDatabaseAdapterFactory wallet) {
            return new TenantBoundBuyerWalletReadConfiguration().cloudBuyerWalletReadPort(router, accounts, wallet);
        }

        @Bean
        BuyerCreditExposureReadConfiguration.CloudWorkforceCreditExposure cloudWorkforceCreditForHttpTest(
                TenantBusinessDatabaseRouter router, TenantBuyerCreditExposureReaderFactory credit,
                TenantCustomerAccountQueryFactory accounts) {
            return new BuyerCreditExposureReadConfiguration.CloudReads().cloudWorkforceCreditExposure(router, credit, accounts);
        }

        @Bean
        @Primary
        BuyerCreditExposureReader cloudBuyerCreditExposureReadsForHttpTest(TenantBusinessDatabaseRouter router,
                TenantBuyerCreditExposureReaderFactory credit, TenantCustomerAccountQueryFactory accounts) {
            return new BuyerCreditExposureReadConfiguration.CloudReads()
                    .cloudBuyerCreditExposureReads(router, credit, accounts);
        }

        @Bean
        @Primary
        BuyerDeliveryTrackingRequestRunner cloudBuyerDeliveryTrackingReadsForHttpTest(
                TenantBusinessDatabaseRouter router, TenantBuyerDeliveryTrackingQueryFactory tracking,
                TenantCustomerAccountQueryFactory accounts, TenantSalesOrderFulfillmentQueryFactory orders) {
            return new BuyerDeliveryTrackingReadConfiguration.CloudReads()
                    .cloudBuyerDeliveryTrackingReads(router, tracking, accounts, orders);
        }
        @Bean
        TenantSalesCommitmentCompositionProvider cloudSalesProviderForJourneyTest(
                org.springframework.beans.factory.config.AutowireCapableBeanFactory factory) {
            return factory.createBean(TenantSalesCommitmentCompositionProviderAdapter.class);
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
