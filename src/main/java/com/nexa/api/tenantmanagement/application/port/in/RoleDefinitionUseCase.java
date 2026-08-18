package com.nexa.api.tenantmanagement.application.port.in;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.RoleDefinitionModels.CreateCommand;
import com.nexa.api.tenantmanagement.application.model.RoleDefinitionModels.UpdateCommand;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinition;

import java.util.List;

public interface RoleDefinitionUseCase {
	List<RoleDefinition> list(CurrentAccessContext context);
	RoleDefinition get(CurrentAccessContext context, String id);
	RoleDefinition create(CurrentAccessContext context, CreateCommand command);
	RoleDefinition update(CurrentAccessContext context, String id, UpdateCommand command, long expectedVersion);
	RoleDefinition deactivate(CurrentAccessContext context, String id, long expectedVersion);
}
