package com.nexa.api.salescommitment.application.salesorder.export.port;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.modulith.NamedInterface;

import java.util.Objects;

@NamedInterface("sales-public")
public interface SalesOrderSummaryExportUseCase {
	SalesOrderSummaryExportResult export(CurrentAccessContext context, String orderId,
			SalesOrderSummaryExportFormat format);

	/** Checks export capability before a Tenant router acquires a business database connection. */
	static void requireExportPermission(CurrentAccessContext context) {
		Objects.requireNonNull(context, "Access context is required");
		if (context.hasRole(MembershipRole.BUYER)) context.requirePermission(PermissionKey.BUYER_ORDER_READ);
		else context.requirePermission(PermissionKey.ORDER_EXPORT_READ);
	}
}
