package com.nexa.api.tenantaccessgovernance.iam.legalacceptance;

import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels.Evidence;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.LegalAcceptanceStore;
import com.nexa.api.tenantaccessgovernance.iam.application.service.LegalAcceptance;
import com.nexa.api.tenantaccessgovernance.iam.presentation.rest.LegalAcceptanceController;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalAcceptanceControllerTests {
    @Test void acceptanceUsesOnlyJwtSubjectAndRejectsStaleDocument() throws Exception {
        var rows = new HashMap<UUID, Evidence>();
        LegalAcceptanceStore store = new LegalAcceptanceStore() {
            public Optional<Evidence> find(UUID user, String document, String version) { return Optional.ofNullable(rows.get(user)); }
            public void insertIfAbsent(UUID user, String document, String version, String digest) { rows.putIfAbsent(user, new Evidence(digest, Instant.EPOCH)); }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new LegalAcceptanceController(new LegalAcceptance(store))).build();
        UUID user = UUID.randomUUID();
        var jwt = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).build());
        String body = "{\"documentId\":\"mobile-terms\",\"version\":\"1\",\"contentDigest\":\"" + LegalAcceptance.DIGEST + "\"}";
        mvc.perform(post("/api/v1/me/legal-acceptance").principal(jwt).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        assertThat(rows.keySet()).containsExactly(user);
        mvc.perform(post("/api/v1/me/legal-acceptance").principal(jwt).contentType("application/json")
                .content("{\"documentId\":\"mobile-terms\",\"version\":\"old\",\"contentDigest\":\"wrong\"}"))
                .andExpect(status().isConflict());
        assertThat(rows).hasSize(1);
    }
    @Test void missingOrMalformedPrincipalFailsClosed() {
        LegalAcceptanceStore store = new LegalAcceptanceStore() {
            public Optional<Evidence> find(UUID user, String document, String version) { throw new AssertionError("Unexpected store access"); }
            public void insertIfAbsent(UUID user, String document, String version, String digest) { throw new AssertionError("Unexpected store access"); }
        };
        var controller = new LegalAcceptanceController(new LegalAcceptance(store));
        var malformed = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "none").subject("not-a-uuid").build());
        assertThatThrownBy(() -> controller.current(null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> controller.current(malformed)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void callerCannotChooseAnotherUserInBody() throws Exception {
        var rows = new HashMap<UUID, Evidence>();
        LegalAcceptanceStore store = new LegalAcceptanceStore() {
            public Optional<Evidence> find(UUID user, String document, String version) { return Optional.ofNullable(rows.get(user)); }
            public void insertIfAbsent(UUID user, String document, String version, String digest) { rows.putIfAbsent(user, new Evidence(digest, Instant.EPOCH)); }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new LegalAcceptanceController(new LegalAcceptance(store))).build();
        var actor = UUID.randomUUID();
        var other = UUID.randomUUID();
        var jwt = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "none").subject(actor.toString()).build());
        var body = "{\"documentId\":\"mobile-terms\",\"version\":\"1\",\"contentDigest\":\"" + LegalAcceptance.DIGEST + "\",\"userId\":\"" + other + "\"}";
        var response = mvc.perform(post("/api/v1/me/legal-acceptance").principal(jwt).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(200, 400);
        assertThat(rows).doesNotContainKey(other);
        if (response.getStatus() == 200) assertThat(rows.keySet()).containsExactly(actor);
        else assertThat(rows).isEmpty();
    }

}
