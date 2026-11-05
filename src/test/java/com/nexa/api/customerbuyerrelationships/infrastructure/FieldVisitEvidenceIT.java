package com.nexa.api.customerbuyerrelationships.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named="nexa.integration.enabled", matches="true")
class FieldVisitEvidenceIT extends NexaWorkflowIntegrationSupport {
    @Test void currentRelationshipEvidenceReplaysOnceAndCannotChangeBody() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String salesMembership = membershipId(SALES_EMAIL);
        String customer = buyerClientAccountId();
        var account = mockMvc.perform(get("/api/v1/client-accounts/"+customer).header("Authorization","Bearer "+owner))
            .andExpect(status().isOk()).andReturn();
        mockMvc.perform(put("/api/v1/client-accounts/"+customer+"/sales-owner")
            .header("Authorization","Bearer "+owner).header("If-Match",account.getResponse().getHeader("ETag"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"membershipId\":\""+salesMembership+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.salesOwnerMembershipId").value(salesMembership));
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        var detail = mockMvc.perform(get("/api/v1/client-accounts/"+customer).header("Authorization","Bearer "+sales))
            .andExpect(status().isOk()).andReturn();
        String version = "\""+json(detail).get("version").asLong()+"\"";
        String key = "visit-"+uuid(), path = "/api/v1/client-accounts/"+customer+"/field-visits";
        String body = "{\"purpose\":\"Review customer demand\",\"outcome\":\"Follow up through an authorized purchase request\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}";
        var first = mockMvc.perform(post(path).header("Authorization","Bearer "+sales).header("Idempotency-Key",key)
            .header("If-Match",version).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.clientAccountId").value(customer)).andReturn();
        var replay = mockMvc.perform(post(path).header("Authorization","Bearer "+sales).header("Idempotency-Key",key)
            .header("If-Match",version).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        assertThat(json(replay).get("id").asText()).isEqualTo(json(first).get("id").asText());
        mockMvc.perform(post(path).header("Authorization","Bearer "+sales).header("Idempotency-Key",key)
            .header("If-Match",version).contentType(MediaType.APPLICATION_JSON).content(body.replace("Review customer demand","Changed purpose")))
            .andExpect(status().isConflict());
        assertThat(tenantJdbc().queryForObject("select count(*) from sales.field_visit_evidence where tenant_id=? and workspace_id=? and idempotency_key=?",
            Long.class, UUID.fromString(tenantId()),UUID.fromString(workspaceId()),key)).isEqualTo(1);
        mockMvc.perform(post(path).header("Authorization","Bearer "+sales).header("Idempotency-Key","stale-"+uuid())
            .header("If-Match","\"999999\"").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());
        String buyer = accessToken(BUYER_EMAIL,"PORTAL");
        mockMvc.perform(post(path).header("Authorization","Bearer "+buyer).header("Idempotency-Key",key)
            .header("If-Match",version).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }
}
