package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import com.nexa.api.tenantaccessgovernance.iam.application.model.PasswordResetDeliveryChannel;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordResetDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;

/** Provider-neutral SMTP delivery. Network delivery occurs only in the outbox worker. */
@Component
@Profile("!test")
public final class SmtpPasswordResetDeliveryAdapter implements PasswordResetDeliveryPort {
    private final JavaMailSender sender;
    private final String host;
    private final String from;
    private final String platformUrl;
    private final String portalUrl;
    private final String platformNativeUrl;
    private final String portalNativeUrl;
    private final Duration timeout;
    private final boolean required;

    public SmtpPasswordResetDeliveryAdapter(JavaMailSender sender,
            @Value("${nexa.security.smtp.host:}") String host,
            @Value("${nexa.security.smtp.from:no-reply@nexa.local}") String from,
            @Value("${nexa.security.reset.platform-url:http://localhost:4200/reset-password}") String platformUrl,
            @Value("${nexa.security.reset.portal-url:http://localhost:4300/reset-password}") String portalUrl,
            @Value("${nexa.security.reset.platform-native-url:}") String platformNativeUrl,
            @Value("${nexa.security.reset.portal-native-url:}") String portalNativeUrl,
            @Value("${nexa.security.smtp.timeout:PT5S}") Duration timeout,
            @Value("${nexa.security.smtp.required:false}") boolean required) {
        this.sender = sender; this.host = host == null ? "" : host.trim(); this.from = from;
        this.platformUrl = platformUrl; this.portalUrl = portalUrl;
        this.platformNativeUrl = platformNativeUrl == null ? "" : platformNativeUrl.trim();
        this.portalNativeUrl = portalNativeUrl == null ? "" : portalNativeUrl.trim();
        this.timeout = timeout; this.required = required;
        if (required && this.host.isBlank()) throw new IllegalStateException("SMTP password-reset delivery is required but NEXA_SMTP_HOST is not configured");
    }

    @Override
    public void sendReset(String email, String surface, String token, Instant expiresAt) {
        sendReset(email, surface, token, expiresAt, null);
    }

    @Override
    public void sendReset(String email, String surface, String token, Instant expiresAt, String deliveryKey) {
        sendReset(email, surface, PasswordResetDeliveryChannel.WEB, token, expiresAt, deliveryKey);
    }

    @Override
    public void sendReset(String email, String surface, PasswordResetDeliveryChannel channel,
            String token, Instant expiresAt, String deliveryKey) {
        if (host.isBlank() && !required) return;
        String resetLink = resetLink(surface, channel, token, platformUrl, portalUrl, platformNativeUrl, portalNativeUrl);
        send(email, "Nexa password reset", "Use this link to reset your Nexa password: " + resetLink
                + "\n\nThis link expires at " + expiresAt + ".", deliveryKey);
    }

    static String resetLink(String surface, PasswordResetDeliveryChannel channel, String token,
            String platformUrl, String portalUrl, String platformNativeUrl, String portalNativeUrl) {
        if (channel == null) throw new IllegalArgumentException("Password-reset delivery channel is required");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Password-reset token is required");
        boolean portal = switch (surface == null ? "" : surface.toUpperCase(java.util.Locale.ROOT)) {
            case "PORTAL" -> true;
            case "PLATFORM" -> false;
            default -> throw new IllegalArgumentException("Password-reset surface is invalid");
        };
        String base = switch (channel) {
            case WEB -> portal ? portalUrl : platformUrl;
            case NATIVE -> portal ? portalNativeUrl : platformNativeUrl;
        };
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("Configured password-reset URL is required for " + channel + " " + surface);
        }
        return UriComponentsBuilder.fromUriString(base).fragment("token={token}")
                .encode().buildAndExpand(token).toUriString();
    }

    @Override
    public void sendPasswordChanged(String email, String surface) {
        sendPasswordChanged(email, surface, null);
    }

    @Override
    public void sendPasswordChanged(String email, String surface, String deliveryKey) {
        if (host.isBlank() && !required) return;
        send(email, "Nexa password changed", "Your Nexa password was changed. If you did not request this change, contact your administrator.", deliveryKey);
    }

    @Override
    public void sendInvitation(String email, String displayName, String token, Instant expiresAt) {
        sendInvitation(email, displayName, token, expiresAt, null);
    }

    @Override
    public void sendInvitation(String email, String displayName, String token, Instant expiresAt, String deliveryKey) {
        if (host.isBlank() && !required) return;
        String invitationUrl = platformUrl.replace("/reset-password", "/accept-invitation") + "?token=" + token;
        send(email, "Nexa workspace invitation", "Hello " + displayName + ",\n\nAccept your Nexa workspace invitation: " + invitationUrl
                + "\n\nThis invitation expires at " + expiresAt + ".", deliveryKey);
    }

    private void send(String recipient, String subject, String body, String deliveryKey) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false);
            helper.setFrom(from); helper.setTo(recipient); helper.setSubject(subject); helper.setText(body);
            if (deliveryKey != null && !deliveryKey.isBlank()) message.setHeader("X-Nexa-Delivery-Key", deliveryKey);
            sender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Password notification delivery failed", exception);
        }
    }
}
