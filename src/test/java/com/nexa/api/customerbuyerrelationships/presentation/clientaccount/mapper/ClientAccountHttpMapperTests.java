package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.mapper;

import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.RegisterBuyerClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAccountHttpMapperTests {
	private static final JsonMapper JSON = JsonMapper.shared();

	@Test
	void buyerRegistrationRequiresExplicitAccountTypeAndMapperDoesNotDefaultIt() {
		RegisterBuyerClientAccountRequest request = new RegisterBuyerClientAccountRequest(
				null, null, null, null, null, null, "+51999999999", null);

		try (var factory = Validation.buildDefaultValidatorFactory()) {
			assertThat(factory.getValidator().validate(request))
					.anyMatch(violation -> violation.getPropertyPath().toString().equals("accountType"));
		}
		assertThat(new ClientAccountHttpMapper().buyerRegistration(request).accountType()).isNull();
	}

	@Test
	void workforceAccountListIncludesItsAssignedSellerForBomAudit() {
		var account = new ClientAccountView("account-id", "BUY-1", "Buyer Co", "Buyer", "PE", "RUC",
				"20123456789", "B2B", "Buyer Person", "buyer@example.test", "+51999999999", "", "CASH",
				"ACTIVE", "buyer-membership-id", 4, ClientAccountType.BUSINESS, "sales-membership-id");

		assertThat(new ClientAccountHttpMapper().summary(account).salesOwnerMembershipId())
				.isEqualTo("sales-membership-id");
	}

	@Test
	void buyerSelfProjectionOmitsSharedAccountContactAndMembershipIdentityAcrossTwoBuyers() throws Exception {
		var firstBuyerView = new ClientAccountView("account-id", "BUY-1", "Buyer Co", "Buyer", "PE", "RUC",
				"20123456789", "B2B", "First Buyer", "first@example.test", "+51999999999", "", "CASH",
				"ACTIVE", "first-membership-id", 5, ClientAccountType.BUSINESS);
		var secondBuyerView = new ClientAccountView("account-id", "BUY-1", "Buyer Co", "Buyer", "PE", "RUC",
				"20123456789", "B2B", "First Buyer", "first@example.test", "+51999999999", "", "CASH",
				"ACTIVE", "second-membership-id", 5, ClientAccountType.BUSINESS);
		var mapper = new ClientAccountHttpMapper();

		for (var view : java.util.List.of(firstBuyerView, secondBuyerView)) {
			var response = JSON.readTree(JSON.writeValueAsString(mapper.buyerSelf(view)));
			assertThat(response.path("id").asText()).isEqualTo("account-id");
			assertThat(response.path("businessName").asText()).isEqualTo("Buyer Co");
			assertThat(response.has("contactPerson")).isFalse();
			assertThat(response.has("contactEmail")).isFalse();
			assertThat(response.has("phone")).isFalse();
			assertThat(response.has("buyerMembershipId")).isFalse();
			assertThat(response.path("accountType").asText()).isEqualTo("BUSINESS");
		}
	}
}
