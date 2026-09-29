package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpSecurityRuntimeConfigurationValidatorTests {
    @Test
    void acceptsRequiredStartTlsAndPublicHttpsResetLinks() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true");

        new SmtpSecurityRuntimeConfigurationValidator(environment);
    }

    @Test
    void acceptsImplicitSmtpSsl() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.ssl.enable]", "true");

        new SmtpSecurityRuntimeConfigurationValidator(environment);
    }

    @Test
    void acceptsSmtpsProtocol() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.protocol", "smtps");

        new SmtpSecurityRuntimeConfigurationValidator(environment);
    }

    @Test
    void rejectsStartTlsWhenNotRequired() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");

        assertThatThrownBy(() -> new SmtpSecurityRuntimeConfigurationValidator(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid secure runtime configuration: SMTP transport must require STARTTLS or use implicit SSL");
    }

    @Test
    void rejectsInsecureResetLink() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true");
        environment.setProperty("nexa.security.reset.platform-url", "http://portal.nexa.example/reset-password");

        assertInvalidLink(environment, "nexa.security.reset.platform-url");
    }

    @Test
    void rejectsLoopbackResetLink() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true");
        environment.setProperty("nexa.security.reset.portal-url", "https://127.0.0.1/reset-password");

        assertInvalidLink(environment, "nexa.security.reset.portal-url");
    }

    @Test
    void rejectsPlaceholderResetLink() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true");
        environment.setProperty("nexa.security.reset.platform-url", "https://portal.example.com/reset-password");

        assertInvalidLink(environment, "nexa.security.reset.platform-url");
    }

    @Test
    void rejectsResetLinkUserInfo() {
        MockEnvironment environment = smtpEnvironment();
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true");
        environment.setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true");
        environment.setProperty("nexa.security.reset.platform-url", "https://user:password@portal.nexa.com/reset-password");

        assertInvalidLink(environment, "nexa.security.reset.platform-url");
    }

    @Test
    void doesNotRequireTransportOrHttpsLinksWhenSmtpDeliveryIsInactive() {
        new SmtpSecurityRuntimeConfigurationValidator(new MockEnvironment());
    }

    @Test
    void permitsLocalMailpitAndLocalResetLinks() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("spring.mail.host", "localhost");
        environment.setProperty("nexa.security.reset.platform-url", "http://localhost:4200/reset-password");
        environment.setProperty("nexa.security.reset.portal-url", "http://127.0.0.1:4300/reset-password");

        new SmtpSecurityRuntimeConfigurationValidator(environment);
    }

    @Test
    void permitsTestMailMocks() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("spring.mail.host", "smtp.example.net");
        environment.setProperty("nexa.security.reset.platform-url", "http://localhost:4200/reset-password");
        environment.setProperty("nexa.security.reset.portal-url", "http://localhost:4300/reset-password");

        new SmtpSecurityRuntimeConfigurationValidator(environment);
    }

    private static MockEnvironment smtpEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.mail.host", "smtp.nexa.example");
        environment.setProperty("nexa.security.smtp.host", "smtp.nexa.example");
        environment.setProperty("nexa.security.reset.platform-url", "https://platform.nexa.com/reset-password");
        environment.setProperty("nexa.security.reset.portal-url", "https://portal.nexa.com/reset-password");
        return environment;
    }

    private static void assertInvalidLink(MockEnvironment environment, String property) {
        assertThatThrownBy(() -> new SmtpSecurityRuntimeConfigurationValidator(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid secure runtime configuration: " + property
                        + " must be an absolute HTTPS URL with a valid public host and no userinfo or placeholder");
    }
}
