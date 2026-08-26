package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL races for the v0.14 scarce-resource transaction boundary. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CommercialInventoryConcurrencyIT extends NexaWorkflowIntegrationSupport {

    @Test
    void concurrentDirectOrderRetriesWithSameKeyCreateOneLogicalEffect() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String key = "direct-concurrent-same-key-" + uuid();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> directOrder(sales, key, directBody(buyerClientAccountId(), "IMMEDIATE", "1")));
            Future<MvcResult> second = executor.submit(() -> directOrder(sales, key, directBody(buyerClientAccountId(), "IMMEDIATE", "1")));
            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(json(firstResult).get("id").asText()).isEqualTo(json(secondResult).get("id").asText());
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from sales.idempotency_record where tenant_id=?::uuid and workspace_id=?::uuid "
                        + "and operation='direct-order' and idempotency_key=?",
                Integer.class, tenantId(), workspaceId(), key)).isEqualTo(1);
        UUID orderId = UUID.fromString(jdbc.queryForObject(
                "select resource_id from sales.idempotency_record where tenant_id=?::uuid and workspace_id=?::uuid "
                        + "and operation='direct-order' and idempotency_key=?",
                String.class, tenantId(), workspaceId(), key));
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from integration.outbox_event where aggregate_id=? and event_type='SALES_ORDER_CONFIRMED'", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void competingDirectOrdersCannotOverbookSellableCapacity() throws Exception {
        ensureCommercialInventory();
        BigDecimal capacity = sellableCapacity("CAT-0002", "UNIT");
        assertThat(capacity).isPositive();
        BigDecimal demand = capacity.multiply(new BigDecimal("0.60")).setScale(4, RoundingMode.DOWN);
        if (demand.signum() <= 0) demand = capacity;
        BigDecimal orderDemand = demand;

        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> directOrder(sales, "direct-inventory-race-a-" + uuid(), directBody(buyerClientAccountId(), "IMMEDIATE", orderDemand.toPlainString())));
            Future<MvcResult> second = executor.submit(() -> directOrder(sales, "direct-inventory-race-b-" + uuid(), directBody(buyerClientAccountId(), "IMMEDIATE", orderDemand.toPlainString())));
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409).count()).isEqualTo(1);
        }
    }

    @Test
    void competingCreditLineDirectOrdersCannotExceedCreditAvailability() throws Exception {
        ensureCommercialInventory();
        UUID client = isolatedClientAccount();
        BigDecimal unitPrice = jdbc.queryForObject(
                "select p.amount from catalog_management.sku_price p join catalog_management.sellable_sku s "
                        + "on s.tenant_id=p.tenant_id and s.workspace_id=p.workspace_id and s.id=p.sku_id "
                        + "where p.tenant_id=?::uuid and p.workspace_id=?::uuid and s.legacy_catalog_item_id='CAT-0002' "
                        + "and p.cancelled_at is null and p.valid_from <= current_timestamp and (p.valid_until is null or p.valid_until > current_timestamp) order by p.valid_from desc limit 1",
                BigDecimal.class, tenantId(), workspaceId());
        jdbc.update("update sales.client_account set credit_limit=?,current_commercial_exposure=0,available_credit=?,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=?",
                unitPrice, unitPrice, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), client);

        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> directOrder(sales, "direct-credit-race-a-" + uuid(), directBody(client.toString(), "CREDIT_LINE", "1")));
            Future<MvcResult> second = executor.submit(() -> directOrder(sales, "direct-credit-race-b-" + uuid(), directBody(client.toString(), "CREDIT_LINE", "1")));
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409).count()).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("select credit_exposure + reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                BigDecimal.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), client)).isLessThanOrEqualTo(unitPrice);
    }

    @Test
    void multiSkuDirectOrderUsesDeterministicLockSetAndRollsBackWithoutPartialState() throws Exception {
        ensureCommercialInventory();
        seedInventory("CAT-0001", 10);
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        int commitmentsBefore = jdbc.queryForObject(
                "select count(*) from sales.commercial_commitment where tenant_id=?::uuid and workspace_id=?::uuid and origin_type='DIRECT_ORDER'",
                Integer.class, tenantId(), workspaceId());
        int ordersBefore = jdbc.queryForObject(
                "select count(*) from sales.sales_order where tenant_id=?::uuid and workspace_id=?::uuid and order_source='DIRECT_ORDER'",
                Integer.class, tenantId(), workspaceId());
        int backingBefore = jdbc.queryForObject(
                "select count(*) from warehouse.inventory_backing where tenant_id=?::uuid and workspace_id=?::uuid",
                Integer.class, tenantId(), workspaceId());

        MvcResult rejected = directOrder(sales, "direct-multi-sku-" + uuid(), multiSkuBody());
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);

        assertThat(jdbc.queryForObject(
                "select count(*) from sales.commercial_commitment where tenant_id=?::uuid and workspace_id=?::uuid and origin_type='DIRECT_ORDER'",
                Integer.class, tenantId(), workspaceId())).isEqualTo(commitmentsBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from sales.sales_order where tenant_id=?::uuid and workspace_id=?::uuid and order_source='DIRECT_ORDER'",
                Integer.class, tenantId(), workspaceId())).isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from warehouse.inventory_backing where tenant_id=?::uuid and workspace_id=?::uuid",
                Integer.class, tenantId(), workspaceId())).isEqualTo(backingBefore);
    }

    @Test
    void confirmationAndCancellationRaceHasOneTerminalWinner() throws Exception {
        ensureCommercialInventory();
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult pending = directOrder(sales, "direct-terminal-race-" + uuid(), directBody(buyerClientAccountId(), "PREPAID", "1"));
        assertThat(pending.getResponse().getStatus()).isEqualTo(202);
        UUID orderId = UUID.fromString(json(pending).get("id").asText());
        UUID receivableId = createPrepaidConfirmationEvidence(orderId);
        assertThat(receivableId).isNotNull();
        String etag = pending.getResponse().getHeader("ETag");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> confirm = executor.submit(() -> mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/confirmations")
                            .header("Authorization", "Bearer " + sales).header("If-Match", etag)
                            .header("Idempotency-Key", "terminal-confirm-" + uuid())).andReturn());
            Future<MvcResult> cancel = executor.submit(() -> mockMvc.perform(post("/api/v1/sales-orders/" + orderId + "/cancellations")
                            .header("Authorization", "Bearer " + sales).header("If-Match", etag)
                            .header("Idempotency-Key", "terminal-cancel-" + uuid())).andReturn());
            MvcResult confirmResult = confirm.get(30, TimeUnit.SECONDS);
            MvcResult cancelResult = cancel.get(30, TimeUnit.SECONDS);
            List<Integer> statuses = List.of(confirmResult.getResponse().getStatus(), cancelResult.getResponse().getStatus());

            assertThat(statuses.stream().filter(value -> value == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(value -> value == 409 || value == 412).count()).isEqualTo(1);
        }

        String terminalStatus = jdbc.queryForObject("select status from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), orderId);
        assertThat(terminalStatus).isIn("CONFIRMED", "CANCELLED");
        String commitmentStatus = jdbc.queryForObject("select c.status from sales.commercial_commitment c join sales.sales_order o on o.commercial_commitment_id=c.id where o.id=?",
                String.class, orderId);
        assertThat(commitmentStatus).isIn("CONVERTED", "RELEASED");
    }

    private MvcResult directOrder(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/direct-orders")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private String directBody(String client, String paymentOption, String quantity) {
        return "{\"clientAccountId\":\"" + client + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Concurrency test\",\"paymentOption\":\"" + paymentOption + "\",\"comment\":\"v0.14 concurrency\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":" + quantity + ",\"unit\":\"UNIT\"}]}";
    }

    private String multiSkuBody() {
        return "{\"clientAccountId\":\"" + buyerClientAccountId() + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"2099-12-31\",\"deliveryProfileSnapshot\":\"Multi-SKU concurrency test\",\"paymentOption\":\"IMMEDIATE\",\"comment\":\"v0.14 multi-sku\",\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"},{\"catalogItemId\":\"CAT-0001\",\"quantity\":1000000,\"unit\":\"UNIT\"}]}";
    }

    private void seedInventory(String catalogItemId, int quantity) throws Exception {
        String warehouse = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-RACE-" + suffix + "\",\"name\":\"Concurrency warehouse\",\"address\":\"Lima\"}"))
                .andExpect(status().isCreated()).andReturn();
        String warehouseId = json(createdWarehouse).get("id").asText();
        MvcResult createdZone = mockMvc.perform(post("/api/v1/warehouses/" + warehouseId + "/zones")
                        .header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-RACE-" + suffix + "\",\"name\":\"Concurrency zone\",\"type\":\"AMBIENT\"}"))
                .andExpect(status().isCreated()).andReturn();
        String zoneId = json(createdZone).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/inbound-receipts")
                        .header("Authorization", "Bearer " + warehouse)
                        .header("Idempotency-Key", "race-inbound-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + warehouseId + "\",\"zoneId\":\"" + zoneId
                                + "\",\"catalogItemId\":\"" + catalogItemId + "\",\"batchNumber\":\"B-RACE-" + suffix
                                + "\",\"expirationDate\":\"2099-01-01\",\"quantity\":" + quantity + ",\"unit\":\"UNIT\"}"))
                .andExpect(status().isCreated());
    }

    private BigDecimal sellableCapacity(String catalogItemId, String unit) {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID sku = jdbc.queryForObject("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and legacy_catalog_item_id=?",
                UUID.class, tenant, workspace, catalogItemId);
        List<WarehouseCapacity> warehouses = jdbc.query("select l.warehouse_id,coalesce(sum(l.stock_quantity-l.reserved_quantity),0) from warehouse.inventory_lot l join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id where l.tenant_id=? and l.workspace_id=? and l.status='AVAILABLE' and l.expiration_date>current_date and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' and l.catalog_item_id=? and l.unit=? and l.stock_quantity>l.reserved_quantity group by l.warehouse_id",
                (rs, row) -> new WarehouseCapacity(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenant, workspace, catalogItemId, unit);
        return warehouses.stream().map(warehouse -> warehouse.stock()
                .subtract(jdbc.queryForObject("select coalesce(sum(quantity),0) from warehouse.safety_stock_policy where tenant_id=? and workspace_id=? and warehouse_id=? and sku_id=?",
                        BigDecimal.class, tenant, workspace, warehouse.id(), sku))
                .subtract(jdbc.queryForObject("select coalesce(sum(p.quantity),0) from warehouse.inventory_backing_position p join warehouse.inventory_backing_line l on l.tenant_id=p.tenant_id and l.workspace_id=p.workspace_id and l.id=p.backing_line_id join warehouse.inventory_backing b on b.tenant_id=l.tenant_id and b.workspace_id=l.workspace_id and b.id=l.backing_id where p.tenant_id=? and p.workspace_id=? and p.warehouse_id=? and l.sku_id=? and b.status='BACKED'",
                        BigDecimal.class, tenant, workspace, warehouse.id(), sku)).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private UUID isolatedClientAccount() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into sales.client_account(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                id, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), "RACE-" + id.toString().substring(0, 8), "Race Client", "Race Client", "PE", "RUC", "20" + id.toString().replace("-", "").substring(0, 11), "STANDARD", "Race Contact", "race-" + id + "@example.invalid", "000000000", "Lima", "NET30", Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private UUID createPrepaidConfirmationEvidence(UUID orderId) throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/receivables")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "prepaid-receivable-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"SALES_ORDER\",\"subjectId\":\"" + orderId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID receivable = UUID.fromString(json(created).get("id").asText());
        BigDecimal amount = jdbc.queryForObject("select amount from payments.receivable where id=?", BigDecimal.class, receivable);
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        Instant now = Instant.now();
        jdbc.update("update payments.receivable set amount_paid=?,status='PAID',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=?",
                amount, Timestamp.from(now), tenant, workspace, receivable);
        jdbc.update("insert into payments.payment(id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,metadata,created_at,updated_at,completed_at) select ?,tenant_id,workspace_id,client_account_id,?,?,'CARD_STRIPE','SUCCEEDED',amount,currency,'TEST',?,'{}'::jsonb,?,?,? from payments.receivable where id=?",
                UUID.randomUUID(), receivable, UUID.fromString(membershipId(SALES_EMAIL)), "prepaid-success-" + uuid(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), receivable);
        return receivable;
    }

    private record WarehouseCapacity(UUID id, BigDecimal stock) { }
}
