package com.nexa.api.salescommitment.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SalesArchitectureTests {
	private static final Path SALES_SOURCE = Path.of("src/main/java/com/nexa/api/salescommitment");

	@Test void salesOrderCanonicalTypesExistOnlyInSalesOrderPackage() throws Exception {
		for (String type : List.of("SalesOrder.java", "SalesOrderId.java", "SalesOrderStatus.java", "SalesOrderLine.java")) {
			List<Path> matches;
			try (var paths = Files.walk(SALES_SOURCE)) { matches = paths.filter(path -> path.getFileName().toString().equals(type)).toList(); }
			assertThat(matches).singleElement().satisfies(path -> assertThat(path.toString()).contains("domain/model/salesorder"));
		}
	}

	@Test void salesDomainContainsNoFrameworkTypes() throws Exception {
		try (var paths = Files.walk(SALES_SOURCE.resolve("domain"))) {
			for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).toList()) {
				String source = Files.readString(path);
				assertThat(source).doesNotContain("org.springframework", "jakarta.persistence", "jakarta.validation", "com.fasterxml.jackson");
			}
		}
	}

	@Test void presentationDoesNotExposeApplicationModels() throws Exception {
		try (var paths = Files.walk(SALES_SOURCE.resolve("presentation"))) {
			for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).toList()) {
				assertThat(Files.readString(path)).doesNotContain("application.model.ClientAccountView", "application.model.PurchaseRequestView", "application.model.PurchaseRequestLineView");
			}
		}
	}
}
