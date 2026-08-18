package com.nexa.api.payments.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class PaymentIntegrationSupport extends NexaWorkflowIntegrationSupport {
    private static final String LOCAL_STRIPE_SECRET = "whsec_local_service_foundation";
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    protected OpenReceivable createOpenReceivable() throws Exception {
        SalesOrderResource order = createConfirmedSalesOrder();
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/receivables")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "receivable-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"SALES_ORDER\",\"subjectId\":\"" + order.id() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        var value = json(created);
        return new OpenReceivable(UUID.fromString(value.get("id").asText()), order.id(),
                new BigDecimal(value.get("amount").asText()), value.get("currency").asText());
    }

    protected PaymentIntentFixture createCardPayment() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/payment-intents")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "payment-intent-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        var value = json(created);
        return new PaymentIntentFixture(receivable, buyer, value.get("paymentId").asText(),
                value.get("providerPaymentIntentId").asText(), value.get("clientSecret").asText());
    }

    protected String uploadAvailableProof(String buyerToken, String receivableId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "proof.png", "image/png", ONE_PIXEL_PNG);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/business-document-evidence")
                        .file(file)
                        .param("subjectType", "RECEIVABLE")
                        .param("subjectId", receivableId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .header("Idempotency-Key", "proof-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        return json(uploaded).get("id").asText();
    }

    protected String stripePayload(String eventId, String eventType, String paymentIntentId,
                                   String status, long amountMinor, String currency,
                                   String tenantId, String workspaceId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + eventType
                + "\",\"payment_intent_id\":\"" + paymentIntentId + "\",\"status\":\"" + status
                + "\",\"amount\":" + amountMinor + ",\"currency\":\"" + currency
                + "\",\"nexa_tenant_id\":\"" + tenantId + "\",\"nexa_workspace_id\":\"" + workspaceId + "\"}";
    }

    protected String stripeSignature(String payload) {
        return stripeSignature(Instant.now().getEpochSecond(), payload);
    }

    protected String stripeSignature(long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(LOCAL_STRIPE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String digest = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + digest;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    protected UUID tenantUuid() { return UUID.fromString(tenantId()); }

    protected UUID workspaceUuid() { return UUID.fromString(workspaceId()); }

    protected ForeignReceivable createForeignReceivable() {
        UUID foreignTenant = UUID.randomUUID();
        UUID foreignWorkspace = UUID.randomUUID();
        UUID foreignClient = UUID.randomUUID();
        UUID foreignReceivable = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                foreignTenant, "Foreign payment tenant", "foreign-payment-" + foreignTenant, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                foreignWorkspace, foreignTenant, "Foreign payment workspace", "foreign-payment-" + foreignWorkspace, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                foreignClient, foreignTenant, foreignWorkspace, "F-" + foreignClient.toString().substring(0, 8), "Foreign Client", "Foreign Client",
                "PE", "RUC", "20" + foreignClient.toString().replace("-", "").substring(0, 11), "B2B", "Foreign Contact",
                "foreign@example.test", "+51000000000", "Lima", "NET_30", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into payments.receivable (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,due_at,status,created_at,updated_at) values (?,?,?,?,? ,?, ?, 'PEN', 10.00, ?, 'OPEN', ?, ?)",
                foreignReceivable, foreignTenant, foreignWorkspace, foreignClient, "SALES_ORDER", UUID.randomUUID(),
                "AR-FOREIGN-" + foreignReceivable.toString().substring(0, 8), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now), Timestamp.from(now));
        return new ForeignReceivable(foreignTenant, foreignWorkspace, foreignReceivable);
    }

    protected record OpenReceivable(UUID id, String salesOrderId, BigDecimal amount, String currency) { }
    protected record PaymentIntentFixture(OpenReceivable receivable, String buyerToken, String paymentId,
                                          String providerPaymentIntentId, String clientSecret) { }
    protected record ForeignReceivable(UUID tenantId, UUID workspaceId, UUID receivableId) { }
}
