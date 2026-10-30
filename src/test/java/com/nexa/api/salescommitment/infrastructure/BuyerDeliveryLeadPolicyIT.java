package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.salescommitment.application.policy.BuyerDeliveryLeadBusinessPolicy;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@Import(BuyerDeliveryLeadPolicyIT.BuyerDeliveryLeadClockConfiguration.class)
class BuyerDeliveryLeadPolicyIT extends NexaWorkflowIntegrationSupport {
    private static final String POLICY_PATH = "/api/v1/buyer/delivery-lead-policy";
    private static final String DRAFT_PATH = "/api/v1/buyer/purchase-request-drafts";

    @org.springframework.beans.factory.annotation.Autowired
    private MutableTenantSalesTime businessTime;

    @BeforeEach
    void resetBusinessClock() {
        businessTime.set(Clock.systemUTC().instant());
    }

    @Test
    void policyEndpointRequiresBuyerPortalScopeAndReturnsTheExactGlobalContract() throws Exception {
        var policyCalculator = new BuyerDeliveryLeadBusinessPolicy();
        LocalDate beforeRequestMinimum = policyCalculator.minimumAllowedDeliveryDate(Clock.systemUTC());
        mockMvc.perform(get(POLICY_PATH)).andExpect(status().isUnauthorized());

        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get(POLICY_PATH).header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult policy = mockMvc.perform(get(POLICY_PATH).header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.minimumDeliveryLeadBusinessDays").value(5))
                .andExpect(jsonPath("$.timezone").value("America/Lima"))
                .andReturn();

        var body = json(policy);
        LocalDate afterRequestMinimum = policyCalculator.minimumAllowedDeliveryDate(Clock.systemUTC());
        LocalDate returnedMinimum = LocalDate.parse(body.get("minimumAllowedDeliveryDate").asText());
        assertThat(returnedMinimum).isIn(beforeRequestMinimum, afterRequestMinimum);
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.has("tenantId")).isFalse();
        assertThat(body.has("workspaceId")).isFalse();
    }

    @Test
    void directOrderCannotBypassTheGlobalDeliveryLeadPolicy() throws Exception {
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        String clientAccountId = buyerClientAccountId();
        LocalDate tooSoon = new BuyerDeliveryLeadBusinessPolicy()
                .minimumAllowedDeliveryDate(businessTime.clock()).minusDays(1);
        String idempotencyKey = "direct-lead-" + uuid();

        mockMvc.perform(post("/api/v1/direct-orders")
                        .header("Authorization", "Bearer " + sales)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + clientAccountId
                                + "\",\"priority\":\"NORMAL\",\"requestedDeliveryDate\":\"" + tooSoon
                                + "\",\"deliveryProfileSnapshot\":\"Direct order delivery\","
                                + "\"paymentOption\":\"IMMEDIATE\",\"comment\":\"lead policy integration\","
                                + "\"lines\":[{\"catalogItemId\":\"CAT-0002\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(tenantJdbc().queryForObject("select count(*) from sales.idempotency_record "
                        + "where tenant_id=? and workspace_id=? and operation='direct-order' and idempotency_key=?",
                Integer.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), idempotencyKey)).isZero();
    }

    @Test
    void legacyPurchaseRequestAllowsMissingDateInDraftButNotSubmission() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult draft = mockMvc.perform(post("/api/v1/purchase-requests")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(draft).get("requestedDeliveryDate").isNull()).isTrue();
        String requestId = json(draft).get("id").asText();
        mockMvc.perform(post("/api/v1/purchase-requests/" + requestId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", draft.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", "missing-lead-" + uuid()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void draftCreateReviewAndSubmitUseCurrentBusinessClockAndReplayIdempotently() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID clientAccount = UUID.fromString(buyerClientAccountId());
        UUID addressId = UUID.randomUUID();
        var policyCalculator = new BuyerDeliveryLeadBusinessPolicy();
        Instant initialInstant = businessTime.instant();
        Clock businessClock = businessTime.clock();
        LocalDate initialMinimum = policyCalculator.minimumAllowedDeliveryDate(businessClock);

        int insertedAddress = tenantJdbc().update("insert into sales.client_account_address "
                        + "(id,tenant_id,workspace_id,client_account_id,label,recipient_name,address_line,"
                        + "department_code,province_code,district_code,source,default_address,status,version,"
                        + "created_at,updated_at,latitude,longitude) "
                        + "values (?,?,?,?,?,?,?,?,?,?,'MANUAL',false,'ACTIVE',0,current_timestamp,current_timestamp,?,?)",
                addressId, tenant, workspace, clientAccount, "Delivery lead integration", "Buyer recipient",
                "Av. Lima 123", "15", "1501", "150101", -12.0464, -77.0428);
        assertThat(insertedAddress).isEqualTo(1);

        String skuId = tenantJdbc().queryForObject("""
                select s.id::text
                from catalog_management.sellable_sku s
                join warehouse.inventory_lot l on l.tenant_id=s.tenant_id and l.workspace_id=s.workspace_id and l.sku_id=s.id
                where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible
                  and l.status='AVAILABLE' and l.expiration_date >= current_date
                  and l.stock_quantity-l.reserved_quantity >= 1
                  and l.warehouse_id = (
                      select l2.warehouse_id
                      from warehouse.inventory_lot l2
                      where l2.tenant_id=s.tenant_id and l2.workspace_id=s.workspace_id
                        and l2.status='AVAILABLE' and l2.expiration_date >= current_date
                        and l2.stock_quantity-l2.reserved_quantity >= 1
                      group by l2.warehouse_id
                      order by count(distinct l2.sku_id) desc, l2.warehouse_id
                      limit 1
                  )
                group by s.id, s.sku_code
                order by s.sku_code
                limit 1
                """, String.class, tenant, workspace);

        MvcResult created = mockMvc.perform(post(DRAFT_PATH)
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientAccountId\":\"" + clientAccount + "\",\"requestedDeliveryDate\":\""
                                + initialMinimum + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID draftId = UUID.fromString(json(created).get("id").asText());

        MvcResult lines = mockMvc.perform(put(DRAFT_PATH + "/" + draftId + "/lines")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"skuId\":\"" + skuId + "\",\"quantity\":1,\"unit\":\"UNIT\"}]}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult destination = mockMvc.perform(put(DRAFT_PATH + "/" + draftId + "/destination")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", lines.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult route = mockMvc.perform(post(DRAFT_PATH + "/" + draftId + "/route-previews")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", destination.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"LOCAL_ESTIMATE\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult preferences = mockMvc.perform(put(DRAFT_PATH + "/" + draftId + "/preferences")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", route.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentPreference\":\"CASH\",\"requestedDeliveryDate\":\""
                                + initialMinimum + "\"}"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get(DRAFT_PATH + "/" + draftId + "/review")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyToSubmit").value(true));

        businessTime.set(initialInstant.plus(Duration.ofDays(3)));
        LocalDate advancedMinimum = policyCalculator.minimumAllowedDeliveryDate(businessClock);
        assertThat(advancedMinimum).isAfter(initialMinimum);

        String idempotencyKey = "lead-submit-" + uuid();
        mockMvc.perform(post(DRAFT_PATH + "/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", preferences.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.purchase_request_draft_idempotency "
                        + "where tenant_id=? and workspace_id=? and idempotency_key=?", Integer.class,
                tenant, workspace, idempotencyKey)).isZero();
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.purchase_request where id=?", Integer.class,
                draftId)).isZero();

        MvcResult updatedPreferences = mockMvc.perform(put(DRAFT_PATH + "/" + draftId + "/preferences")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", preferences.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentPreference\":\"CASH\",\"requestedDeliveryDate\":\""
                                + advancedMinimum + "\"}"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get(DRAFT_PATH + "/" + draftId + "/review")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyToSubmit").value(true));

        MvcResult submitted = mockMvc.perform(post(DRAFT_PATH + "/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", updatedPreferences.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post(DRAFT_PATH + "/" + draftId + "/submissions")
                        .header("Authorization", "Bearer " + buyer)
                        .header("If-Match", updatedPreferences.getResponse().getHeader("ETag"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();

        assertThat(json(replay).get("id").asText()).isEqualTo(json(submitted).get("id").asText());
        assertThat(tenantJdbc().queryForObject("select requested_delivery_date from sales.purchase_request where id=?",
                LocalDate.class, draftId)).isEqualTo(advancedMinimum);
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.purchase_request where id=?", Integer.class,
                draftId)).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.commercial_commitment where tenant_id=? "
                        + "and workspace_id=? and purchase_request_id=?", Integer.class, tenant, workspace, draftId))
                .isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.purchase_request_draft_idempotency "
                        + "where tenant_id=? and workspace_id=? and idempotency_key=?", Integer.class,
                tenant, workspace, idempotencyKey)).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BuyerDeliveryLeadClockConfiguration {
        @Bean
        MutableTenantSalesTime buyerDeliveryLeadTime() {
            return new MutableTenantSalesTime(Clock.systemUTC().instant());
        }

        @Bean
        @Primary
        TenantSalesCommitmentCompositionFactory buyerDeliveryLeadTenantCompositionFactory(
                TenantSalesCommitmentCompositionFactoryAdapter delegate, MutableTenantSalesTime integrationTime) {
            return (tenantJdbc, bindings) -> delegate.bindTo(tenantJdbc,
                    new TenantSalesCommitmentCompositionFactory.Bindings(bindings.customerAccounts(),
                            bindings.customerAddresses(), bindings.sellableSkus(), bindings.creditExposure(),
                            bindings.catalogItemSnapshots(), bindings.creditReservations(), bindings.warehouses(),
                            bindings.inventoryBacking(), bindings.walletReservations(), bindings.paymentConfirmations(),
                            bindings.receivables(), bindings.financialAdjustments(), bindings.canonicalOutbox(),
                            bindings.changeFeed(), bindings.maps(), integrationTime.clock(), bindings.objectMapper(),
                            bindings.walletTenderEnabled(), bindings.walletRefundBalances()));
        }
    }

    static final class MutableTenantSalesTime {
        private final AtomicReference<Instant> instant;
        private final Clock clock;

        MutableTenantSalesTime(Instant initialInstant) {
            instant = new AtomicReference<>(initialInstant);
            clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return new ZoneClock(instant, zone);
                }

                @Override
                public Instant instant() {
                    return instant.get();
                }
            };
        }

        void set(Instant value) {
            instant.set(value);
        }

        Instant instant() {
            return instant.get();
        }

        Clock clock() {
            return clock;
        }
    }

    private static final class ZoneClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private ZoneClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new ZoneClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
