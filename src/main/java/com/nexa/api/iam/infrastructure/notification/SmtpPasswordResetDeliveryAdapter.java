package com.nexa.api.iam.infrastructure.notification;

import com.nexa.api.iam.application.port.out.PasswordResetDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Provider-neutral SMTP delivery. Mailpit and an unauthenticated internal relay use the same adapter. */
@Component
@Profile("!test")
public final class SmtpPasswordResetDeliveryAdapter implements PasswordResetDeliveryPort {
	private final String host;
	private final int port;
	private final String from;
	private final String platformUrl;
	private final String portalUrl;
	private final Duration timeout;
	private final boolean required;

	public SmtpPasswordResetDeliveryAdapter(
			@Value("${nexa.security.smtp.host:}") String host,
			@Value("${nexa.security.smtp.port:1025}") int port,
			@Value("${nexa.security.smtp.from:no-reply@nexa.local}") String from,
			@Value("${nexa.security.reset.platform-url:http://localhost:4200/reset-password}") String platformUrl,
			@Value("${nexa.security.reset.portal-url:http://localhost:4300/reset-password}") String portalUrl,
			@Value("${nexa.security.smtp.timeout:PT5S}") Duration timeout,
			@Value("${nexa.security.smtp.required:false}") boolean required) {
		this.host = host == null ? "" : host.trim();
		this.port = port;
		this.from = from;
		this.platformUrl = platformUrl;
		this.portalUrl = portalUrl;
		this.timeout = timeout;
		this.required = required;
		if (required && this.host.isBlank()) {
			throw new IllegalStateException("SMTP password-reset delivery is required but NEXA_SMTP_HOST is not configured");
		}
	}

	@Override
	public void sendReset(String email, String surface, String token, Instant expiresAt) {
		if (host.isBlank()) return;
		String base = "PORTAL".equalsIgnoreCase(surface) ? portalUrl : platformUrl;
		send(email, "Nexa password reset", "Use this link to reset your Nexa password: " + base + "?token=" + token
				+ "\n\nThis link expires at " + expiresAt + ".");
	}

	@Override
	public void sendPasswordChanged(String email, String surface) {
		if (host.isBlank()) return;
		send(email, "Nexa password changed", "Your Nexa password was changed. If you did not request this change, contact your administrator.");
	}

	private void send(String recipient, String subject, String body) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
			socket.setSoTimeout((int) timeout.toMillis());
			try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
				expect(in, 220); command(out, "EHLO nexa.local", in, 250);
				command(out, "MAIL FROM:<" + from + ">", in, 250);
				command(out, "RCPT TO:<" + recipient + ">", in, 250);
				command(out, "DATA", in, 354);
				out.write("From: " + from + "\r\nTo: " + recipient + "\r\nSubject: " + subject + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body.replace("\n", "\r\n") + "\r\n.\r\n");
				out.flush(); expect(in, 250); command(out, "QUIT", in, 221);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Password notification delivery failed", exception);
		}
	}

	private static void command(BufferedWriter out, String value, BufferedReader in, int expected) throws Exception {
		out.write(value + "\r\n"); out.flush(); expect(in, expected);
	}

	private static void expect(BufferedReader in, int expected) throws Exception {
		String line = in.readLine();
		if (line == null || line.length() < 3 || Integer.parseInt(line.substring(0, 3)) != expected)
			throw new IllegalStateException("SMTP command rejected");
		while (line.length() > 3 && line.charAt(3) == '-') line = in.readLine();
	}
}
