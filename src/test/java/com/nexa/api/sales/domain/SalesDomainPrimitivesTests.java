package com.nexa.api.sales.domain;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestStatus;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderInvariantViolation;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesDomainPrimitivesTests {
	@Test
	void identifiersNormalizeWithoutLosingTypeIdentity() {
		assertThat(new PurchaseRequestId(" pr-001 ").value()).isEqualTo("PR-001");
		assertThat(new SalesOrderId("so-001").toString()).isEqualTo("SO-001");
		assertThat(new ClientAccountId(" cli-001 ")).isEqualTo(new ClientAccountId("CLI-001"));
	}

	@Test
	void identifiersRejectMissingUnsafeAndOversizedValues() {
		assertThatThrownBy(() -> new PurchaseRequestId(null)).isInstanceOf(SalesInvariantViolation.class);
		assertThatThrownBy(() -> new SalesOrderId(" ")).isInstanceOf(SalesOrderInvariantViolation.class);
		assertThatThrownBy(() -> new ClientAccountId("CLI_001")).isInstanceOf(SalesInvariantViolation.class);
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
			PurchaseRequestStatus.CONVERTED_TO_ORDER);
		assertThat(SalesOrderStatus.values()).containsExactly(
			SalesOrderStatus.PENDING,
			SalesOrderStatus.CONFIRMED,
			SalesOrderStatus.REJECTED,
			SalesOrderStatus.CANCELLED);
	}
}
