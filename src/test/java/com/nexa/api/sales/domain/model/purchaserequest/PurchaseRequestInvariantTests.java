package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseRequestInvariantTests {
	private static final CatalogItemSnapshot ITEM = new CatalogItemSnapshot("CAT-001", "Frozen item", "Box", new PriceSnapshot(BigDecimal.TEN, "PEN"));

	@Test void aggregateRequiresLinesBeforeSubmissionAndRejectsDuplicates() {
		var request = PurchaseRequest.draft(new PurchaseRequestId("PR-001"), "CLI-001", new BuyerMembershipId(UUID.randomUUID()));
		assertThatThrownBy(request::submit).isInstanceOf(SalesInvariantViolation.class);
		request.addLine(line("1", "CAT-001"));
		assertThatThrownBy(() -> request.addLine(line("2", "CAT-001"))).isInstanceOf(SalesInvariantViolation.class);
		request.submit();
		assertThat(request.status()).isEqualTo(PurchaseRequestStatus.SUBMITTED);
	}

	@Test void aggregateProtectsEditableAndTerminalStates() {
		var request = PurchaseRequest.draft(new PurchaseRequestId("PR-002"), "CLI-001", new BuyerMembershipId(UUID.randomUUID()));
		request.addLine(line("1", "CAT-001")); request.submit(); request.startReview(); request.approve("approved");
		assertThat(request.status().isTerminal()).isTrue();
		assertThatThrownBy(() -> request.cancel()).isInstanceOf(SalesInvariantViolation.class);
		assertThatThrownBy(() -> request.addLine(line("2", "CAT-002"))).isInstanceOf(SalesInvariantViolation.class);
	}

	@Test void valueObjectsProtectQuantityCommentsDeliveryAndPayment() {
		assertThatThrownBy(() -> new RequestedQuantity(BigDecimal.ZERO)).isInstanceOf(SalesInvariantViolation.class);
		assertThatThrownBy(() -> new RequestComment("x".repeat(2001))).isInstanceOf(SalesInvariantViolation.class);
		assertThatThrownBy(() -> new RequestedDeliveryDate(java.time.LocalDate.now().minusDays(1))).isInstanceOf(SalesInvariantViolation.class);
		assertThat(new PaymentOption("CREDIT").value()).isEqualTo("CREDIT");
	}

	private static PurchaseRequestLine line(String id, String item) { return new PurchaseRequestLine(new PurchaseRequestLineId(UUID.nameUUIDFromBytes(id.getBytes())), new CatalogItemSnapshot(item, "Frozen item", "Box", new PriceSnapshot(BigDecimal.TEN, "PEN")), new RequestedQuantity(BigDecimal.ONE), "unit", null); }
}
