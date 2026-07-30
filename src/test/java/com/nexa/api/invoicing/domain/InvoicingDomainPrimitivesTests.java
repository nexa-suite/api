package com.nexa.api.invoicing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoicingDomainPrimitivesTests {
	@Test
	void identifiersAndInvoiceNumberNormalizeIndependently() {
		assertThat(new InvoiceId(" inv-001 ").value()).isEqualTo("INV-001");
		assertThat(new PaymentId("pay-001").toString()).isEqualTo("PAY-001");
		assertThat(new InvoiceNumber(" f001-00000001 ").value()).isEqualTo("F001-00000001");
		assertThat(new InvoiceId("INV-001").value()).isEqualTo(new InvoiceNumber("INV-001").value());
	}

	@Test
	void rejectsMissingUnsafeAndOversizedValues() {
		assertThatThrownBy(() -> new InvoiceId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PaymentId(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InvoiceNumber("F001/0001")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InvoiceNumber("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesInvoiceAndPaymentStatusVocabularies() {
		assertThat(InvoiceStatus.values()).containsExactly(
			InvoiceStatus.DRAFT,
			InvoiceStatus.ISSUED,
			InvoiceStatus.PAID,
			InvoiceStatus.OVERDUE,
			InvoiceStatus.VOID);
		assertThat(PaymentStatus.values()).containsExactly(
			PaymentStatus.PENDING,
			PaymentStatus.AUTHORIZED,
			PaymentStatus.SETTLED,
			PaymentStatus.FAILED,
			PaymentStatus.REFUNDED);
	}
}
