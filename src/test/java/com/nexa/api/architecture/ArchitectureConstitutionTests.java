package com.nexa.api.architecture;

import com.nexa.api.NexaApiApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureConstitutionTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nexa.api");
    private static final Map<String, Set<String>> TARGET_BOUNDED_CONTEXT_OWNERS = targetBoundedContextOwners();

    @Test
    void springModulithModulesVerify() {
        ApplicationModules modules = ApplicationModules.of(NexaApiApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()).toList())
                .contains("iam", "tenantmanagement", "customerrelationships", "catalogmanagement", "sales", "warehouse",
                        "logistics", "invoicing", "payments", "notifications", "audit", "shared");
        assertDoesNotThrow(() -> modules.verify());
    }

    @Test
    void targetHasExactlyElevenSemanticBoundedContextsWithConcreteOwners() {
        assertThat(TARGET_BOUNDED_CONTEXT_OWNERS.keySet()).containsExactly(
                "Tenant & Access Governance",
                "Customer & Buyer Relationships",
                "Catalog & Commercial Policy",
                "Sales Commitment",
                "Inventory Availability",
                "Fulfillment & Delivery",
                "Credit & Receivables",
                "Payments",
                "Business Documents",
                "Notifications",
                "Business Traceability");
        assertThat(TARGET_BOUNDED_CONTEXT_OWNERS).hasSize(11);
        TARGET_BOUNDED_CONTEXT_OWNERS.values().stream().flatMap(Set::stream)
                .forEach(owner -> assertThat(CLASSES.stream().anyMatch(type -> type.getPackageName().startsWith(owner)))
                        .as("concrete owner package %s", owner).isTrue());
    }

    @Test
    void customerRelationshipAuthorityDoesNotLeakThroughPersistencePorts() {
        assertThat(CLASSES.stream()
                .filter(type -> type.getSimpleName().equals("ClientAccountPersistenceAdapter")
                        || type.getSimpleName().equals("ClientAccountAddressPersistenceAdapter"))
                .map(type -> type.getPackageName()).toList())
                .containsOnly("com.nexa.api.customerrelationships.infrastructure.persistence");

        assertThat(CLASSES.stream()
                .filter(type -> !type.getPackageName().startsWith("com.nexa.api.customerrelationships"))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getTargetClass().getSimpleName().equals("ClientAccountPersistencePort")
                        || dependency.getTargetClass().getSimpleName().equals("ClientAccountAddressPersistencePort"))
                .map(Object::toString).toList()).isEmpty();
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

    private static Map<String, Set<String>> targetBoundedContextOwners() {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        owners.put("Tenant & Access Governance", Set.of("com.nexa.api.iam", "com.nexa.api.tenantmanagement"));
        owners.put("Customer & Buyer Relationships", Set.of("com.nexa.api.customerrelationships"));
        owners.put("Catalog & Commercial Policy", Set.of("com.nexa.api.catalogmanagement"));
        owners.put("Sales Commitment", Set.of("com.nexa.api.sales"));
        owners.put("Inventory Availability", Set.of("com.nexa.api.warehouse"));
        owners.put("Fulfillment & Delivery", Set.of("com.nexa.api.logistics"));
        owners.put("Credit & Receivables", Set.of("com.nexa.api.sales.domain.model.credit"));
        owners.put("Payments", Set.of("com.nexa.api.payments"));
        owners.put("Business Documents", Set.of("com.nexa.api.invoicing"));
        owners.put("Notifications", Set.of("com.nexa.api.notifications"));
        owners.put("Business Traceability", Set.of("com.nexa.api.audit"));
        return Collections.unmodifiableMap(owners);
    }
}
