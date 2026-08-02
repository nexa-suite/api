package com.nexa.api.architecture;

import com.nexa.api.NexaApiApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureConstitutionTests {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.nexa.api");

    @Test
    void springModulithModulesVerify() {
        ApplicationModules modules = ApplicationModules.of(NexaApiApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()).toList())
                .contains("iam", "tenantmanagement", "catalogmanagement", "sales", "warehouse", "logistics", "invoicing", "shared");
        assertDoesNotThrow(() -> modules.verify());
    }

    @Test
    void domainDoesNotDependOnOuterLayers() { domainDoesNotDependOnOuterLayers.check(CLASSES); }
    private static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAnyPackage("..domain..").and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..presentation..", "org.springframework..", "jakarta..", "com.fasterxml..", "tools.jackson..");

    @Test
    void applicationDoesNotDependOnPresentationOrJdbc() { applicationDoesNotDependOnPresentationOrJdbc.check(CLASSES); }
    private static final ArchRule applicationDoesNotDependOnPresentationOrJdbc = noClasses()
            .that().resideInAnyPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..presentation..", "org.springframework.jdbc..", "java.sql..");

    @Test
    void presentationDoesNotDependDirectlyOnPersistenceAdapters() { presentationDoesNotDependDirectlyOnPersistenceAdapters.check(CLASSES); }
    private static final ArchRule presentationDoesNotDependDirectlyOnPersistenceAdapters = noClasses()
            .that().resideInAnyPackage("..presentation..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure.persistence..");

    @Test
    void controllersStayInPresentation() { controllersStayInPresentation.check(CLASSES); }
    private static final ArchRule controllersStayInPresentation = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAnyPackage("..presentation..");

    @Test
    void infrastructureCannotImplementInboundApplicationPorts() {
        List<String> violations = CLASSES.stream()
                .filter(type -> type.getPackageName().contains(".infrastructure."))
                .filter(type -> type.getInterfaces().stream()
                        .anyMatch(port -> port.toErasure().getPackageName().contains(".application.port.in")))
                .map(type -> type.getName()).toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void iamHasNoGodInboundSecurityInterface() {
        assertThat(Files.exists(Path.of("src/main/java/com/nexa/api/iam/application/port/in/IamSecurityUseCase.java"))).isFalse();
        assertThat(CLASSES.stream().map(type -> type.getSimpleName()).filter("IamSecurityUseCase"::equals)).isEmpty();
    }
}
