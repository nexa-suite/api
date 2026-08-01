package com.nexa.api.shared.application.error;

public class ApiResourceNotFoundException extends RuntimeException {
	private final String resource;

	public ApiResourceNotFoundException(String resource) {
		super(resource + " was not found");
		this.resource = resource;
	}

	public String resource() { return resource; }
}
