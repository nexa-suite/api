package com.nexa.api.sales.application.salesorder.export.service;

import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryProjectionPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SalesOrderSummaryExportService implements SalesOrderSummaryExportUseCase {
	private final SalesOrderSummaryProjectionPort projection;
	private final CustomerAccountQuery accounts;
	private final SalesOrderSummaryRendererStrategy renderers;

	public SalesOrderSummaryExportService(SalesOrderSummaryProjectionPort projection, CustomerAccountQuery accounts,
			SalesOrderSummaryRendererStrategy renderers) {
		this.projection = Objects.requireNonNull(projection, "Sales order summary projection is required");
		this.accounts = Objects.requireNonNull(accounts, "Client accounts are required");
		this.renderers = Objects.requireNonNull(renderers, "Summary renderers are required");
	}

	@Override
	public SalesOrderSummaryExportResult export(CurrentAccessContext context, String orderId,
			SalesOrderSummaryExportFormat format) {
		Objects.requireNonNull(context, "Access context is required");
		Objects.requireNonNull(format, "Export format is required");
		String tenantId = context.tenantId().toString();
		String workspaceId = context.workspaceId().toString();
		String clientAccountId = clientScope(context, tenantId, workspaceId);
		SalesOrderSummarySnapshot snapshot = projection.find(tenantId, workspaceId, clientAccountId, orderId)
				.orElseThrow(() -> new SalesResourceNotFoundException("sales-order"));
		if (!tenantId.equals(snapshot.tenantId()) || !workspaceId.equals(snapshot.workspaceId())) {
			throw new AccessPolicyViolation("Sales order is outside the current tenant workspace");
		}
		if (clientAccountId != null && !clientAccountId.equals(snapshot.clientAccountId())) {
			throw new AccessPolicyViolation("Sales order is outside the current buyer account");
		}
		byte[] content = renderers.render(snapshot, format);
		return new SalesOrderSummaryExportResult(filename(snapshot.number(), format), format.contentType(), content);
	}

	private String clientScope(CurrentAccessContext context, String tenantId, String workspaceId) {
		if (!context.hasRole(MembershipRole.BUYER)) {
			context.requirePermission(PermissionKey.ORDER_EXPORT_READ);
			return null;
		}
		context.requirePermission(PermissionKey.BUYER_ORDER_READ);
		return accounts.findBuyerReference(tenantId, workspaceId, context.membershipId().toString())
				.map(CustomerAccountReference::id)
				.orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
	}

	static String filename(String orderNumber, SalesOrderSummaryExportFormat format) {
		String safeNumber = orderNumber == null ? "order" : orderNumber.replaceAll("[^A-Za-z0-9._-]", "-");
		if (safeNumber.isBlank()) safeNumber = "order";
		if (safeNumber.length() > 80) safeNumber = safeNumber.substring(0, 80);
		return "nexa-order-summary-" + safeNumber + "." + format.extension();
	}
}
