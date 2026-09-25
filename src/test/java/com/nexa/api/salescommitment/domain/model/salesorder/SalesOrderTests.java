package com.nexa.api.salescommitment.domain.model.salesorder;

import com.nexa.api.customerbuyerrelationships.contract.CustomerAccountId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesOrderTests {
	@Test void createsOnlyFromApprovedSnapshotAndSupportsCommercialTransitions() {
		var line = new SalesOrderLine("CAT-001", "Frozen item", BigDecimal.ONE, BigDecimal.TEN, "PEN");
		var snapshot = new ApprovedPurchaseRequestSnapshot(new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new CustomerAccountId("CLI-001"), new PurchaseRequestId("PR-001"), List.of(line), BigDecimal.TEN);
		var order = SalesOrder.fromApprovedSnapshot(snapshot, new SalesOrderId("SO-001"), new SalesOrderNumber("SO-2026-000001"), MembershipId.random(), Instant.EPOCH);
		assertThat(order.status()).isEqualTo(SalesOrderStatus.CONFIRMED);
		assertThat(order.confirmedAt()).isEqualTo(Instant.EPOCH);
		assertThat(order.sourcePurchaseRequestId().value()).isEqualTo("PR-001");
	}
	@Test void terminalCommercialStatesCannotBeReused() {
		var line = new SalesOrderLine("CAT-001", "Frozen item", BigDecimal.ONE, BigDecimal.TEN, "PEN");
		var snapshot = new ApprovedPurchaseRequestSnapshot(new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new CustomerAccountId("CLI-001"), new PurchaseRequestId("PR-001"), List.of(line), BigDecimal.TEN);
		var order = SalesOrder.fromApprovedSnapshot(snapshot, new SalesOrderId("SO-001"), new SalesOrderNumber("SO-2026-000002"), MembershipId.random(), Instant.EPOCH);
		order.cancel(Instant.EPOCH.plusSeconds(1));
		assertThatThrownBy(() -> order.confirm(Instant.now())).isInstanceOf(SalesOrderInvariantViolation.class);
		assertThatThrownBy(order::reject).isInstanceOf(SalesOrderInvariantViolation.class);
	}
	@Test void confirmedOrderCanBeCancelledForPostPaymentSettlement() {
		var line = new SalesOrderLine("CAT-001", "Frozen item", BigDecimal.ONE, BigDecimal.TEN, "PEN");
		var snapshot = new ApprovedPurchaseRequestSnapshot(new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new CustomerAccountId("CLI-001"), new PurchaseRequestId("PR-001"), List.of(line), BigDecimal.TEN);
		var order = SalesOrder.fromApprovedSnapshot(snapshot, new SalesOrderId("SO-001"), new SalesOrderNumber("SO-2026-000003"), MembershipId.random(), Instant.EPOCH);
		assertThat(order.status()).isEqualTo(SalesOrderStatus.CONFIRMED);
		order.cancel(Instant.EPOCH.plusSeconds(2));
		assertThat(order.status()).isEqualTo(SalesOrderStatus.CANCELLED);
		assertThat(order.cancelledAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
	}

	@Test void assistedOrderRehydrationKeepsUnknownBuyerIdentityNull() {
		var line = new SalesOrderLine("CAT-001", "Frozen item", BigDecimal.ONE, BigDecimal.TEN, "PEN");
		var order = SalesOrder.rehydrate(new SalesOrderId("SO-ASSISTED"), new SalesOrderNumber("SO-2026-000004"),
				new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new CustomerAccountId("CLI-001"),
				null, null, MembershipId.random(), List.of(line), null, null, "Assisted order", null, null,
				"PEN", BigDecimal.TEN, Instant.EPOCH, SalesOrderStatus.PENDING, null, null, null, null, 0);

		assertThat(order.buyerMembershipId()).isNull();
		assertThat(order.createdByMembershipId()).isNotNull();
	}
}
