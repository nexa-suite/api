package com.nexa.api.tenantmanagement.application.exception;

public final class RoleDefinitionPersistenceUnavailableException extends RuntimeException {
	public RoleDefinitionPersistenceUnavailableException() {
		super("Role definition persistence is not integrated in this checkout");
	}
}
