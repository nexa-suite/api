package com.nexa.api.shared.presentation.error;

public final class ApiResourceNotFoundException extends com.nexa.api.shared.application.error.ApiResourceNotFoundException {

	public ApiResourceNotFoundException(String resource) {
		super(resource);
	}
}
