package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fails startup when security-token email delivery could use an unsafe transport or link. */
@Component
@Profile("!local & !test")
public final class SmtpSecurityRuntimeConfigurationValidator {
    private static final Set<String> PLACEHOLDER_LABELS = Set.of(
            "example", "invalid", "placeholder", "changeme", "change-me", "yourdomain",
            "your-domain", "replace", "replace-me", "dummy", "sample", "todo", "test");

    public SmtpSecurityRuntimeConfigurationValidator(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local", "test")) || !smtpConfigured(environment)) return;

        Map<String, String> mailProperties = Binder.get(environment)
                .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
        if (!secureTransport(environment, mailProperties)) {
            fail("SMTP transport must require STARTTLS or use implicit SSL");
        }

        validateLink(environment, "nexa.security.reset.platform-url");
        validateLink(environment, "nexa.security.reset.portal-url");
    }

    private static boolean smtpConfigured(Environment environment) {
        return hasText(environment.getProperty("spring.mail.host"))
                || hasText(environment.getProperty("nexa.security.smtp.host"))
                || Boolean.parseBoolean(environment.getProperty("nexa.security.smtp.required", "false"));
    }

    private static boolean secureTransport(Environment environment, Map<String, String> mailProperties) {
        boolean startTlsEnabled = mailProperty(environment, mailProperties, "mail.smtp.starttls.enable");
        boolean startTlsRequired = mailProperty(environment, mailProperties, "mail.smtp.starttls.required");
        boolean smtpSslEnabled = mailProperty(environment, mailProperties, "mail.smtp.ssl.enable");

        String protocol = environment.getProperty("spring.mail.protocol", "smtp").trim();
        boolean smtpsProtocol = "smtps".equalsIgnoreCase(protocol)
                && !"false".equalsIgnoreCase(mailPropertyValue(environment, mailProperties, "mail.smtps.ssl.enable"));
        return (startTlsEnabled && startTlsRequired) || smtpSslEnabled || smtpsProtocol;
    }

    private static boolean mailProperty(Environment environment, Map<String, String> mailProperties, String key) {
        return Boolean.parseBoolean(mailPropertyValue(environment, mailProperties, key));
    }

    private static String mailPropertyValue(Environment environment, Map<String, String> mailProperties, String key) {
        String configured = environment.getProperty("spring.mail.properties[" + key + "]");
        if (configured == null) configured = environment.getProperty("spring.mail.properties." + key);
        if (configured == null) configured = mailProperties.get(key);
        return configured == null ? "" : configured.trim();
    }

    private static void validateLink(Environment environment, String property) {
        String value = environment.getProperty(property, "").trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            fail(property + " must be an absolute HTTPS URL with a valid public host and no userinfo or placeholder");
            return;
        }

        String host = uri.getHost();
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || host.isBlank() || uri.getRawUserInfo() != null || !validHost(host)
                || unsafeHost(host) || uri.getPort() == 0 || uri.getPort() > 65535) {
            fail(property + " must be an absolute HTTPS URL with a valid public host and no userinfo or placeholder");
        }
    }

    private static boolean validHost(String host) {
        String normalized = unbracket(host);
        if (normalized.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(normalized).getAddress().length == 16;
            } catch (Exception exception) {
                return false;
            }
        }
        if (normalized.matches("[0-9.]+")) {
            String[] octets = normalized.split("\\.", -1);
            if (octets.length != 4) return false;
            for (String octet : octets) {
                try {
                    int value = Integer.parseInt(octet);
                    if (value < 0 || value > 255 || octet.length() > 1 && octet.startsWith("0")) return false;
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
            return true;
        }
        try {
            String ascii = IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES);
            if (ascii.isBlank() || ascii.length() > 253) return false;
            for (String label : ascii.replaceFirst("\\.$", "").split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean unsafeHost(String host) {
        String normalized = unbracket(host).toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("localhost.localdomain") || normalized.endsWith(".local")) return true;
        if (isLoopbackAddress(normalized)) return true;
        for (String label : normalized.split("\\.")) {
            if (PLACEHOLDER_LABELS.contains(label)) return true;
        }
        return false;
    }

    private static boolean isLoopbackAddress(String host) {
        if (host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (Exception exception) {
                return true;
            }
        }
        if (!host.matches("[0-9.]+")) return false;
        String[] octets = host.split("\\.", -1);
        return octets.length == 4 && octets[0].equals("127");
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void fail(String message) {
        throw new IllegalStateException("Invalid secure runtime configuration: " + message);
    }
}
