package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDomainPurityTests {
	@Test
	void domainSourcesHaveNoFrameworkTransportOrSqlImports() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/nexa/api/catalogmanagement/domain"))) {
			String source = files.filter(path -> path.toString().endsWith(".java"))
					.map(this::read)
					.reduce("", String::concat);
			assertThat(source).doesNotContain("org.springframework", "jakarta.persistence", "com.fasterxml.jackson", "java.sql");
		}
	}

	private String read(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
