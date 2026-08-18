package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.exception.CatalogIdempotencyKeyRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogIdempotencyContractTests {
	@Test
	void acceptsAValidIdempotencyKey() {
		assertThatCode(() -> CatalogHttpSupport.requireIdempotency("catalog-command-001"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingAndBlankKeys() {
		assertThatThrownBy(() -> CatalogHttpSupport.requireIdempotency(null))
				.isInstanceOf(CatalogIdempotencyKeyRequiredException.class);
		assertThatThrownBy(() -> CatalogHttpSupport.requireIdempotency("  "))
				.isInstanceOf(CatalogIdempotencyKeyRequiredException.class);
	}

	@Test
	void rejectsKeysLongerThanTheExistingContractLimit() {
		assertThatThrownBy(() -> CatalogHttpSupport.requireIdempotency("x".repeat(161)))
				.isInstanceOf(CatalogIdempotencyKeyRequiredException.class);
	}
}
