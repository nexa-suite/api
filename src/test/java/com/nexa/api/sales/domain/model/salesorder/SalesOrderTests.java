package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
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
		var snapshot = new ApprovedPurchaseRequestSnapshot(new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new ClientAccountId("CLI-001"), new PurchaseRequestId("PR-001"), List.of(line), BigDecimal.TEN);
		var order = SalesOrder.fromApprovedSnapshot(snapshot, new SalesOrderId("SO-001"), new SalesOrderNumber("SO-001"), Instant.EPOCH);
		order.confirm(Instant.EPOCH.plusSeconds(1));
		assertThat(order.status()).isEqualTo(SalesOrderStatus.CONFIRMED);
		assertThat(order.sourcePurchaseRequestId().value()).isEqualTo("PR-001");
	}
	@Test void terminalCommercialStatesCannotBeReused() {
		var line = new SalesOrderLine("CAT-001", "Frozen item", BigDecimal.ONE, BigDecimal.TEN, "PEN");
		var snapshot = new ApprovedPurchaseRequestSnapshot(new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()), new ClientAccountId("CLI-001"), new PurchaseRequestId("PR-001"), List.of(line), BigDecimal.TEN);
		var order = SalesOrder.fromApprovedSnapshot(snapshot, new SalesOrderId("SO-001"), new SalesOrderNumber("SO-001"), Instant.EPOCH);
		order.reject();
		assertThatThrownBy(() -> order.confirm(Instant.now())).isInstanceOf(SalesOrderInvariantViolation.class);
	}
}
