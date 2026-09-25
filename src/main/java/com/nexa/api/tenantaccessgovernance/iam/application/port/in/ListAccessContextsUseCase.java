package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextOption;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketQuery;

import java.util.List;

public interface ListAccessContextsUseCase {
	List<AccessContextOption> listAccessContexts(AccessContextTicketQuery query);
}
