package com.nexa.api.invoicing.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDocumentArchitectureTests {
    private static final Path INVOICING_SOURCE = Path.of("src/main/java/com/nexa/api/invoicing");

    @Test void domainIsFrameworkFreeAndDoesNotDependOnInfrastructure() throws Exception {
        try (var paths = Files.walk(INVOICING_SOURCE.resolve("domain"))) {
            for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(path)).doesNotContain("org.springframework", "jakarta.persistence", "jakarta.validation", "com.fasterxml.jackson", ".infrastructure");
            }
        }
    }

    @Test void foundationHasNoControllerOrStorageBoundary() throws Exception {
        try (var paths = Files.walk(INVOICING_SOURCE.resolve("application"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase();
                assertThat(name).doesNotContain("controller", "upload", "download", "persistence");
            }
        }
    }

    @Test void stableLogisticsSubjectIdentitiesRemainOutsideInvoicing() {
        assertThat(Path.of("src/main/java/com/nexa/api/logistics/domain/dispatchorder/ProofOfDeliveryId.java")).exists();
        assertThat(Path.of("src/main/java/com/nexa/api/logistics/domain/dispatchorder/DeliveryIncidentId.java")).exists();
    }
}
