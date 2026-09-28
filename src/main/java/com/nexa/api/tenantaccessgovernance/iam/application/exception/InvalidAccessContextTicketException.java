package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class InvalidAccessContextTicketException extends RuntimeException {
	public InvalidAccessContextTicketException() { super("Access context credential is invalid or expired"); }
}
