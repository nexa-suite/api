package com.nexa.api.architecture;

import com.nexa.api.NexaApiApplication;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Files;
import java.io.IOException;
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
                .containsExactlyInAnyOrder("BC-01-tenant-access-governance", "BC-02-customer-buyer-relationships",
                        "BC-03-catalog-commercial-policy", "BC-04-sales-commitment", "BC-05-inventory-availability",
                        "BC-06-fulfillment-delivery", "BC-07-credit-receivables", "BC-08-payments",
                        "BC-09-business-documents", "BC-10-notifications", "BC-11-business-traceability",
                        "bootstrap", "shared");
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
        assertThat(TARGET_BOUNDED_CONTEXT_OWNERS.values().stream().flatMap(Set::stream)
                .map(owner -> owner.substring("com.nexa.api.".length()).split("\\.")[0])
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("businessdocuments", "businesstraceability", "catalogcommercialpolicy",
                        "creditreceivables", "customerbuyerrelationships", "fulfillmentdelivery", "inventoryavailability",
                        "notifications", "payments", "salescommitment", "tenantaccessgovernance");
    }

    @Test
    void customerRelationshipAuthorityDoesNotLeakThroughPersistencePorts() {
        assertThat(CLASSES.stream()
                .filter(type -> type.getSimpleName().equals("ClientAccountPersistenceAdapter")
                        || type.getSimpleName().equals("ClientAccountAddressPersistenceAdapter"))
                .map(type -> type.getPackageName()).toList())
                .containsOnly("com.nexa.api.customerbuyerrelationships.infrastructure.persistence");

        assertThat(CLASSES.stream()
                .filter(type -> !type.getPackageName().startsWith("com.nexa.api.customerbuyerrelationships"))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getTargetClass().getSimpleName().equals("ClientAccountPersistencePort")
                        || dependency.getTargetClass().getSimpleName().equals("ClientAccountAddressPersistencePort"))
                .map(Object::toString).toList()).isEmpty();
    }

    @Test
    void salesUsesPublicContractsInsteadOfForeignSqlOrCustomerPresentation() throws IOException {
        String salesSources = sourcesUnder(Path.of("src/main/java/com/nexa/api/salescommitment"));
        assertThat(salesSources).doesNotContain(" from catalog_management.", " join catalog_management.",
                " from payments.", " join payments.", " update payments.", "insert into payments.",
                " from tenant_management.", " join tenant_management.", " from iam.", " join iam.",
                " from warehouse.", " join warehouse.", " from sales.client_account", " join sales.client_account");

        noClasses().that().resideInAPackage("com.nexa.api.salescommitment..")
                .should().dependOnClassesThat().resideInAPackage("com.nexa.api.customerbuyerrelationships.presentation..")
                .check(CLASSES);
    }

    @Test
    void customerRelationshipsDoesNotQueryOtherBoundedContextSchemas() throws IOException {
        assertThat(sourcesUnder(Path.of("src/main/java/com/nexa/api/customerbuyerrelationships")))
                .doesNotContain(" from catalog_management.", " join catalog_management.", " from payments.", " join payments.",
                        " from tenant_management.", " join tenant_management.", " from iam.", " join iam.",
                        " from warehouse.", " join warehouse.");
    }

    @Test
    void customerRelationshipSnapshotExcludesCommercialTermsAndCreditAuthority() {
        assertThat(java.util.Arrays.stream(CustomerAccountDetails.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("id", "code", "businessName", "commercialName", "taxIdentifierType",
                        "taxIdentifierValue", "segment", "status")
                .doesNotContain("paymentCondition", "creditLimit", "creditCurrency",
                        "currentCommercialExposure", "availableCredit");
        assertThat(CustomerTermsQuery.class.getPackageName())
                .isEqualTo("com.nexa.api.catalogcommercialpolicy.application.publicapi");
        assertThat(CreditExposureQuery.class.getPackageName())
                .isEqualTo("com.nexa.api.creditreceivables.application.publicapi");
    }

    @Test
    void domainDoesNotDependOnOuterLayers() { domainDoesNotDependOnOuterLayers.check(CLASSES); }
    private static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAnyPackage("..domain..").and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..presentation..", "org.springframework..", "jakarta..", "com.fasterxml..", "tools.jackson..", "org.hibernate..", "java.sql..", "org.postgresql..");

    @Test
    void applicationDoesNotDependOnPresentationOrJdbc() { applicationDoesNotDependOnPresentationOrJdbc.check(CLASSES); }
    private static final ArchRule applicationDoesNotDependOnPresentationOrJdbc = noClasses()
            .that().resideInAnyPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..presentation..", "org.springframework.jdbc..", "java.sql..", "jakarta.persistence..", "org.hibernate..", "org.postgresql..");

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
        assertThat(Files.exists(Path.of("src/main/java/com/nexa/api/tenantaccessgovernance/iam/application/port/in/IamSecurityUseCase.java"))).isFalse();
        assertThat(CLASSES.stream().map(type -> type.getSimpleName()).filter("IamSecurityUseCase"::equals)).isEmpty();
    }

    private static Map<String, Set<String>> targetBoundedContextOwners() {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        owners.put("Tenant & Access Governance", Set.of("com.nexa.api.tenantaccessgovernance"));
        owners.put("Customer & Buyer Relationships", Set.of("com.nexa.api.customerbuyerrelationships"));
        owners.put("Catalog & Commercial Policy", Set.of("com.nexa.api.catalogcommercialpolicy"));
        owners.put("Sales Commitment", Set.of("com.nexa.api.salescommitment"));
        owners.put("Inventory Availability", Set.of("com.nexa.api.inventoryavailability"));
        owners.put("Fulfillment & Delivery", Set.of("com.nexa.api.fulfillmentdelivery"));
        owners.put("Credit & Receivables", Set.of("com.nexa.api.creditreceivables"));
        owners.put("Payments", Set.of("com.nexa.api.payments"));
        owners.put("Business Documents", Set.of("com.nexa.api.businessdocuments"));
        owners.put("Notifications", Set.of("com.nexa.api.notifications"));
        owners.put("Business Traceability", Set.of("com.nexa.api.businesstraceability"));
        return Collections.unmodifiableMap(owners);
    }

    private static String sourcesUnder(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted().map(path -> {
                        try { return Files.readString(path); }
                        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    }).collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
