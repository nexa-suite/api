package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.mapper;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAccountHttpMapperTests {
    @Test void buyerSelfOmitsUnverifiedAccountContactsAndUsesAuthenticatedMembership() {
        var account = new ClientAccountView("account", "code", "Business", null, "PE", "RUC", "tax", null,
                "Other person", "other@example.invalid", "123", null, null, "ACTIVE", "other-membership", 2);
        var response = new ClientAccountHttpMapper().buyerSelf(account, "authenticated-membership");
        var json = JsonMapper.shared().readTree(JsonMapper.shared().writeValueAsString(response));
        assertThat(json.has("contactPerson")).isFalse();
        assertThat(json.has("contactEmail")).isFalse();
        assertThat(json.has("phone")).isFalse();
        assertThat(json.get("buyerMembershipId").asText()).isEqualTo("authenticated-membership");
        assertThat(json.get("id").asText()).isEqualTo("account");
        for (String field : java.util.List.of("commercialName", "segment", "deliveryProfile", "paymentCondition")) {
            assertThat(json.has(field)).as(field).isTrue();
            assertThat(json.get(field).isNull()).as(field).isTrue();
        }
    }
}
