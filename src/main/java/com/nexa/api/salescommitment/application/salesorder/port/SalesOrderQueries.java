package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.util.List;

@NamedInterface("sales-public")
public interface SalesOrderQueries {
	SalesPage<SalesOrderView> list(CurrentAccessContext context, SalesOrderFilter filter);
	SalesOrderView detail(CurrentAccessContext context, String id);
	List<SalesOrderEventView> events(CurrentAccessContext context, String id);
}
