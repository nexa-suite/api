package com.nexa.api.tenantaccessgovernance.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SupportConsoleBoundaryTests {
	@Test
	void applicationServiceDoesNotDependOnHttpStatusTypes() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/nexa/api/tenantaccessgovernance/support/application/service/SupportConsoleService.java"));
		assertThat(source).doesNotContain("org.springframework.http", "org.springframework.web");
	}
}
