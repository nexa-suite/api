package com.nexa.api.sales.application.salesorder.export;

import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryLineSnapshot;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryProjectionPort;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryRenderer;
import com.nexa.api.sales.application.salesorder.export.service.SalesOrderSummaryExportService;
import com.nexa.api.sales.application.salesorder.export.service.SalesOrderSummaryRendererStrategy;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderSummaryExportIsolationTests {
	private static final String TENANT = "3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10";
	private static final String WORKSPACE = "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17";
	private static final String ORDER = "a7a5a7d1-49a0-4d5c-a9d7-0b65784c6991";
	private static final String CLIENT_A = "c7e9ab18-114e-4b91-9bf7-72172aa9a0a4";
	private static final String CLIENT_B = "dbb7ad49-e4ff-42d2-94dd-980b9b19e25b";

	@Test
	void rejectsProjectionThatCrossesWorkspaceBoundary() {
		SalesOrderSummaryProjectionPort projection = mock(SalesOrderSummaryProjectionPort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(projection.find(TENANT, WORKSPACE, null, ORDER)).thenReturn(Optional.of(snapshot(CLIENT_A, "different-workspace")));
		SalesOrderSummaryExportService service = service(projection, accounts);

		assertThatThrownBy(() -> service.export(internalContext(), ORDER, SalesOrderSummaryExportFormat.CSV))
				.isInstanceOf(AccessPolicyViolation.class);
		verify(projection).find(TENANT, WORKSPACE, null, ORDER);
	}

	@Test
	void buyerProjectionIsClientScopedAndFailsClosedOnMismatch() {
		CurrentAccessContext context = buyerContext();
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(accounts.findBuyerReference(TENANT, WORKSPACE, context.membershipId().toString())).thenReturn(Optional.of(client(CLIENT_A)));
		SalesOrderSummaryProjectionPort projection = mock(SalesOrderSummaryProjectionPort.class);
		when(projection.find(TENANT, WORKSPACE, CLIENT_A, ORDER)).thenReturn(Optional.of(snapshot(CLIENT_B, WORKSPACE)));
		SalesOrderSummaryExportService service = service(projection, accounts);

		assertThatThrownBy(() -> service.export(context, ORDER, SalesOrderSummaryExportFormat.PDF))
				.isInstanceOf(AccessPolicyViolation.class);
		verify(projection).find(TENANT, WORKSPACE, CLIENT_A, ORDER);
	}

	private static SalesOrderSummaryExportService service(SalesOrderSummaryProjectionPort projection, CustomerAccountQuery accounts) {
		SalesOrderSummaryRenderer renderer = new SalesOrderSummaryRenderer() {
			@Override public SalesOrderSummaryExportFormat format() { return SalesOrderSummaryExportFormat.CSV; }
			@Override public byte[] render(SalesOrderSummarySnapshot snapshot) { return "ok".getBytes(); }
		};
		return new SalesOrderSummaryExportService(projection, accounts, new SalesOrderSummaryRendererStrategy(List.of(renderer)));
	}

	private static CurrentAccessContext internalContext() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.tenantId()).thenReturn(new TenantId(TENANT));
		when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE));
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(false);
		return context;
	}

	private static CurrentAccessContext buyerContext() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.tenantId()).thenReturn(new TenantId(TENANT));
		when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE));
		when(context.membershipId()).thenReturn(new MembershipId("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2"));
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(true);
		return context;
	}

	private static CustomerAccountReference client(String id) {
		return new CustomerAccountReference(id, "ACTIVE");
	}

	private static SalesOrderSummarySnapshot snapshot(String client, String workspace) {
		return new SalesOrderSummarySnapshot(ORDER, "SO-2026-000001", TENANT, workspace, client, "NORMAL", null, null,
				null, null, "PEN", new BigDecimal("1"), "PENDING", Instant.parse("2026-08-02T10:15:00Z"),
				List.of(new SalesOrderSummaryLineSnapshot("f1d7e4fd-d3f9-42b3-94e3-58c2e5f5bfb8", "Item", "Box", BigDecimal.ONE, "BOX", BigDecimal.ONE, "PEN", BigDecimal.ONE)));
	}
}
