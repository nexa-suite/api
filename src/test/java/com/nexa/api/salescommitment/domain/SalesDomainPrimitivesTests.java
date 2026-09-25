package com.nexa.api.salescommitment.domain;

import com.nexa.api.customerbuyerrelationships.contract.CustomerAccountId;
import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestStatus;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderId;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderStatus;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderInvariantViolation;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyOption;
import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesDomainPrimitivesTests {
	@Test
	void identifiersNormalizeWithoutLosingTypeIdentity() {
		assertThat(new PurchaseRequestId(" pr-001 ").value()).isEqualTo("PR-001");
		assertThat(new SalesOrderId("so-001").toString()).isEqualTo("SO-001");
		assertThat(new CustomerAccountId(" cli-001 ")).isEqualTo(new CustomerAccountId("CLI-001"));
	}

	@Test
	void identifiersRejectMissingUnsafeAndOversizedValues() {
		assertThatThrownBy(() -> new PurchaseRequestId(null)).isInstanceOf(SalesInvariantViolation.class);
		assertThatThrownBy(() -> new SalesOrderId(" ")).isInstanceOf(SalesOrderInvariantViolation.class);
		assertThatThrownBy(() -> new CustomerAccountId("CLI_001")).isInstanceOf(CustomerRelationshipInvariantViolation.class);
		assertThatThrownBy(() -> new PurchaseRequestId("A".repeat(65))).isInstanceOf(SalesInvariantViolation.class);
	}

	@Test
	void exposesCandidateStatusVocabularies() {
		assertThat(PurchaseRequestStatus.values()).containsExactly(
			PurchaseRequestStatus.DRAFT,
			PurchaseRequestStatus.SUBMITTED,
			PurchaseRequestStatus.CHANGES_PROPOSED,
			PurchaseRequestStatus.CONVERTED,
			PurchaseRequestStatus.REJECTED,
			PurchaseRequestStatus.WITHDRAWN,
			PurchaseRequestStatus.EXPIRED,
			PurchaseRequestStatus.IN_REVIEW,
			PurchaseRequestStatus.NEEDS_ADJUSTMENT,
			PurchaseRequestStatus.APPROVED,
			PurchaseRequestStatus.CANCELLED,
			PurchaseRequestStatus.CONVERTED_TO_ORDER);
		assertThat(SalesOrderStatus.values()).containsExactly(
			SalesOrderStatus.PENDING,
			SalesOrderStatus.CONFIRMED,
			SalesOrderStatus.IN_FULFILLMENT,
			SalesOrderStatus.PARTIALLY_FULFILLED,
			SalesOrderStatus.FULFILLED,
			SalesOrderStatus.PARTIALLY_DELIVERED,
			SalesOrderStatus.COMPLETED,
			SalesOrderStatus.REJECTED,
			SalesOrderStatus.CANCELLED);
	}

	@Test
	void legacyPurchaseRequestProjectionPreservesCancellationAndProjectsDeprecatedWorkflowStates() {
		assertThat(PurchaseRequestStatus.IN_REVIEW.currentApiValue()).isEqualTo(PurchaseRequestStatus.SUBMITTED);
		assertThat(PurchaseRequestStatus.NEEDS_ADJUSTMENT.currentApiValue()).isEqualTo(PurchaseRequestStatus.SUBMITTED);
		assertThat(PurchaseRequestStatus.APPROVED.currentApiValue()).isEqualTo(PurchaseRequestStatus.SUBMITTED);
		assertThat(PurchaseRequestStatus.CONVERTED_TO_ORDER.currentApiValue()).isEqualTo(PurchaseRequestStatus.CONVERTED);
		assertThat(PurchaseRequestStatus.CANCELLED.currentApiValue()).isEqualTo(PurchaseRequestStatus.CANCELLED);
	}

	@Test
	void roadTypesAreTopLevelReferenceData() {
		assertThat(new PeruGeographyOption(1, PeruGeographyLevel.ROAD_TYPE, "AVENUE", "Avenida", null, true).parentCode()).isNull();
	}
}
