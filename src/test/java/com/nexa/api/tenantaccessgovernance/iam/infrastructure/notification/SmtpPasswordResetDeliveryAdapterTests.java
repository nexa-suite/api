package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpPasswordResetDeliveryAdapterTests {
    @Test
    void buyerResetLinkUsesFragmentForToken() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var adapter = new SmtpPasswordResetDeliveryAdapter(sender, "smtp.example", "no-reply@example.test",
                "http://localhost:4200/reset-password", "http://localhost:4200/reset-password",
                "", "",
                Duration.ofSeconds(5), true);

        adapter.sendReset("buyer@example.test", "PORTAL", "sample-token", Instant.parse("2026-10-29T20:00:00Z"));

        verify(sender).send(message);
        assertThat(message.getContent().toString())
                .contains("http://localhost:4200/reset-password#token=sample-token")
                .doesNotContain("?token=");
    }

    @Test
    void platformResetLinkUsesConfiguredPlatformDestination() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var adapter = new SmtpPasswordResetDeliveryAdapter(sender, "smtp.example", "no-reply@example.test",
                "https://platform.example.test/reset-password", "https://portal.example.test/reset-password",
                "", "",
                Duration.ofSeconds(5), true);

        adapter.sendReset("member@example.test", "PLATFORM", "sample-token", Instant.parse("2026-10-29T20:00:00Z"));

        verify(sender).send(message);
        assertThat(message.getContent().toString())
                .contains("https://platform.example.test/reset-password#token=sample-token")
                .doesNotContain("portal.example.test", "?token=");
    }
}
