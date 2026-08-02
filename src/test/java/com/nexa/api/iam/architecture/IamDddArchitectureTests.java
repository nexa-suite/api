package com.nexa.api.iam.architecture;

import com.nexa.api.iam.application.port.out.CredentialPersistencePort;
import com.nexa.api.iam.application.port.out.MembershipRolePersistencePort;
import com.nexa.api.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.iam.application.port.out.OrganizationActivationPersistencePort;
import com.nexa.api.iam.application.port.out.OrganizationRegistrationPersistencePort;
import com.nexa.api.iam.application.port.out.PasswordResetPersistencePort;
import com.nexa.api.iam.application.port.out.PasswordResetThrottlePort;
import com.nexa.api.iam.application.port.out.RefreshSessionPersistencePort;
import com.nexa.api.iam.application.port.out.UserProfilePersistencePort;
import com.nexa.api.iam.application.service.OrganizationRegistrationService;
import com.nexa.api.iam.application.service.PasswordResetService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class IamDddArchitectureTests {
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.nexa.api.iam");
    private static final Set<Class<?>> IAM_OUTPUT_PORTS = Set.of(CredentialPersistencePort.class,
            MembershipRolePersistencePort.class, OpaqueSecurityTokenPort.class, OrganizationActivationPersistencePort.class,
            OrganizationRegistrationPersistencePort.class, PasswordResetPersistencePort.class, PasswordResetThrottlePort.class,
            RefreshSessionPersistencePort.class, UserProfilePersistencePort.class);

    @Test
    void domainRemainsFreeOfSpringJdbcAndPresentationDependencies() {
        noClasses().that().resideInAnyPackage("..iam.domain..").and().doNotHaveSimpleName("package-info")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.springframework.jdbc..", "java.sql..", "..iam.presentation..")
                .check(CLASSES);
    }

    @Test
    void infrastructureAdaptersDoNotImplementInboundApplicationPorts() {
        assertThat(CLASSES.stream().filter(type -> type.getPackageName().contains(".iam.infrastructure."))
                .flatMap(type -> type.getInterfaces().stream())
                .filter(type -> type.toErasure().getPackageName().contains(".iam.application.port.in"))
                .toList()).isEmpty();
    }

    @Test
    void eachJdbcAdapterOwnsAtMostOneIamOutputPort() {
        assertThat(CLASSES.stream().filter(type -> type.getPackageName().contains(".iam.infrastructure.security"))
                .map(type -> type.getInterfaces().stream().filter(port -> IAM_OUTPUT_PORTS.contains(port.toErasure().reflect())).count())
                .max(Long::compareTo).orElse(0L)).isLessThanOrEqualTo(1L);
    }

    @Test
    void applicationOrchestratorsReferenceDomainAggregates() {
        assertThat(PasswordResetService.class.getDeclaredFields()).anyMatch(field -> field.getType().equals(com.nexa.api.iam.application.port.out.PasswordResetPersistencePort.class));
        assertThat(OrganizationRegistrationService.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals("restore"));
    }
}
