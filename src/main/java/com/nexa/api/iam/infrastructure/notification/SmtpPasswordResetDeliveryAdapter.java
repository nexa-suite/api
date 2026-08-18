package com.nexa.api.iam.infrastructure.notification;

import com.nexa.api.iam.application.port.out.PasswordResetDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

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
    private final Duration timeout;
    private final boolean required;

    public SmtpPasswordResetDeliveryAdapter(JavaMailSender sender,
            @Value("${nexa.security.smtp.host:}") String host,
            @Value("${nexa.security.smtp.from:no-reply@nexa.local}") String from,
            @Value("${nexa.security.reset.platform-url:http://localhost:4200/reset-password}") String platformUrl,
            @Value("${nexa.security.reset.portal-url:http://localhost:4300/reset-password}") String portalUrl,
            @Value("${nexa.security.smtp.timeout:PT5S}") Duration timeout,
            @Value("${nexa.security.smtp.required:false}") boolean required) {
        this.sender = sender; this.host = host == null ? "" : host.trim(); this.from = from;
        this.platformUrl = platformUrl; this.portalUrl = portalUrl; this.timeout = timeout; this.required = required;
        if (required && this.host.isBlank()) throw new IllegalStateException("SMTP password-reset delivery is required but NEXA_SMTP_HOST is not configured");
    }

    @Override
    public void sendReset(String email, String surface, String token, Instant expiresAt) {
        if (host.isBlank() && !required) return;
        String base = "PORTAL".equalsIgnoreCase(surface) ? portalUrl : platformUrl;
        send(email, "Nexa password reset", "Use this link to reset your Nexa password: " + base + "?token=" + token
                + "\n\nThis link expires at " + expiresAt + ".");
    }

    @Override
    public void sendPasswordChanged(String email, String surface) {
        if (host.isBlank() && !required) return;
        send(email, "Nexa password changed", "Your Nexa password was changed. If you did not request this change, contact your administrator.");
    }

    @Override
    public void sendInvitation(String email, String displayName, String token, Instant expiresAt) {
        if (host.isBlank() && !required) return;
        String invitationUrl = platformUrl.replace("/reset-password", "/accept-invitation") + "?token=" + token;
        send(email, "Nexa workspace invitation", "Hello " + displayName + ",\n\nAccept your Nexa workspace invitation: " + invitationUrl
                + "\n\nThis invitation expires at " + expiresAt + ".");
    }

    private void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(recipient); message.setSubject(subject); message.setText(body);
        try { sender.send(message); }
        catch (RuntimeException exception) { throw new IllegalStateException("Password notification delivery failed", exception); }
    }
}
