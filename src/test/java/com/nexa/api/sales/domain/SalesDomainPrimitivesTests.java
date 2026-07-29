package com.nexa.api.sales.domain;

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
		assertThatThrownBy(() -> new PurchaseRequestId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SalesOrderId(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClientAccountId("CLI_001")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PurchaseRequestId("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
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
			SalesOrderStatus.DRAFT,
			SalesOrderStatus.CONFIRMED,
			SalesOrderStatus.COMPLETED,
			SalesOrderStatus.CANCELLED);
	}
}
