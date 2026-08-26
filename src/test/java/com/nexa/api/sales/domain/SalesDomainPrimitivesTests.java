package com.nexa.api.sales.domain;

import com.nexa.api.customerrelationships.contract.CustomerAccountId;
import com.nexa.api.customerrelationships.contract.CustomerRelationshipInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestStatus;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderInvariantViolation;
import com.nexa.api.sales.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.sales.domain.model.reference.PeruGeographyOption;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
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
			PurchaseRequestStatus.IN_REVIEW,
			PurchaseRequestStatus.NEEDS_ADJUSTMENT,
			PurchaseRequestStatus.APPROVED,
			PurchaseRequestStatus.REJECTED,
			PurchaseRequestStatus.CANCELLED,
			PurchaseRequestStatus.CONVERTED_TO_ORDER,
			PurchaseRequestStatus.EXPIRED,
			PurchaseRequestStatus.WITHDRAWN);
		assertThat(SalesOrderStatus.values()).containsExactly(
			SalesOrderStatus.PENDING,
			SalesOrderStatus.CONFIRMED,
			SalesOrderStatus.REJECTED,
			SalesOrderStatus.CANCELLED);
	}

	@Test
	void roadTypesAreTopLevelReferenceData() {
		assertThat(new PeruGeographyOption(1, PeruGeographyLevel.ROAD_TYPE, "AVENUE", "Avenida", null, true).parentCode()).isNull();
	}
}
