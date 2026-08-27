package com.nexa.api.businessdocuments.infrastructure.security;

import com.nexa.api.businessdocuments.application.port.ContentScannerPort;
import com.nexa.api.shared.infrastructure.observability.TechnicalMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** ClamAV boundary. Deterministic scanning is available only in an explicit local/test mode. */
@Component
@Profile("!test")
public final class ClamAvContentScannerAdapter implements ContentScannerPort {
    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);
    private final String host;
    private final int port;
    private final Mode mode;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final TechnicalMetrics metrics;

    private enum Mode { NETWORK, DETERMINISTIC_LOCAL }

    @Autowired
    public ClamAvContentScannerAdapter(org.springframework.core.env.Environment environment,
                                       ObjectProvider<TechnicalMetrics> metrics) {
        this(environment, metrics == null ? null : metrics.getIfAvailable());
    }

    public ClamAvContentScannerAdapter(org.springframework.core.env.Environment environment) {
        this(environment, (TechnicalMetrics) null);
    }

    private ClamAvContentScannerAdapter(org.springframework.core.env.Environment environment, TechnicalMetrics metrics) {
        this.host = environment.getProperty("nexa.clamav.host", "").trim();
        this.port = Integer.parseInt(environment.getProperty("nexa.clamav.port", "3310"));
        this.mode = parseMode(environment.getProperty("nexa.clamav.mode", "network"), environment);
        this.connectTimeoutMs = positive(environment.getProperty("nexa.clamav.connect-timeout-ms", "3000"), "connect timeout");
        this.readTimeoutMs = positive(environment.getProperty("nexa.clamav.read-timeout-ms", "10000"), "read timeout");
        this.metrics = metrics;
    }

    @Override public ScanResult scan(InputStream input) {
        TechnicalMetrics.TimerSample timer = start("scan");
        try {
            ScanResult result = scanBytes(readBounded(input, 10485760));
            record(timer, outcome(result));
            return result;
        } catch (RuntimeException exception) {
            record(timer, "error");
            throw exception;
        }
    }

    private ScanResult scanBytes(byte[] content) {
        if (content == null || content.length == 0) return new ScanResult(false, null, "EMPTY_FILE");
        if (mode == Mode.NETWORK) {
            if (host.isBlank()) return new ScanResult(false, null, "MALWARE_SCANNER_UNAVAILABLE");
            String verdict = clamScan(content);
            if (verdict == null || verdict.equals("MALWARE_SCANNER_UNAVAILABLE")) return new ScanResult(false, null, "MALWARE_SCANNER_UNAVAILABLE");
            if (verdict.equals("MALWARE_SCANNER_TIMEOUT")) return new ScanResult(false, null, verdict);
            if (!verdict.endsWith("OK")) return new ScanResult(false, null, verdict);
        } else if (contains(content, EICAR)) return new ScanResult(false, null, "MALWARE_SIGNATURE");
        String type = detect(content);
        return type == null ? new ScanResult(false, null, "UNKNOWN_CONTENT_TYPE") : new ScanResult(true, type, "CLEAN");
    }

    private static byte[] readBounded(InputStream input, int maximum) {
        if (input == null) return new byte[0];
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maximum) throw new IllegalArgumentException("Evidence size is invalid");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Content scanner input failed", exception);
        }
    }
    private String clamScan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.getOutputStream().write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            var output = new DataOutputStream(socket.getOutputStream());
            int offset = 0;
            while (offset < content.length) {
                int length = Math.min(1024 * 1024, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
                offset += length;
            }
            output.writeInt(0);
            output.flush();
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != 0) response.write(value);
            String result = response.toString(StandardCharsets.US_ASCII).trim();
            if (result.isBlank()) return "MALFORMED_SCANNER_RESPONSE";
            return result.contains("FOUND") ? "MALWARE_SIGNATURE" : result.contains("OK") ? "OK" : "MALWARE_SCAN_REJECTED";
        } catch (SocketTimeoutException exception) {
            return "MALWARE_SCANNER_TIMEOUT";
        } catch (IOException exception) {
            return "MALWARE_SCANNER_UNAVAILABLE";
        }
    }

    private static Mode parseMode(String value, org.springframework.core.env.Environment environment) {
        String normalized = value == null ? "network" : value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (normalized.equals("deterministic-local")) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("Deterministic malware scanning requires the local or test profile");
            }
            return Mode.DETERMINISTIC_LOCAL;
        }
        if (normalized.equals("network")) return Mode.NETWORK;
        throw new IllegalStateException("Unsupported ClamAV mode '" + value + "'; use network or deterministic-local");
    }

    private static int positive(String value, String label) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalStateException("ClamAV " + label + " must be positive");
        return parsed;
    }
    private TechnicalMetrics.TimerSample start(String operation) { return metrics == null ? null : metrics.start("scanner", operation); }
    private void record(TechnicalMetrics.TimerSample timer, String outcome) {
        if (metrics != null) {
            metrics.count("scanner", "scan", outcome);
            if (timer != null) timer.stop(outcome);
        }
    }
    private static String outcome(ScanResult result) {
        if (result.clean()) return "clean";
        return switch (result.reason()) {
            case "MALWARE_SIGNATURE" -> "malware";
            case "MALWARE_SCANNER_UNAVAILABLE" -> "unavailable";
            case "MALWARE_SCANNER_TIMEOUT" -> "timeout";
            default -> "rejected";
        };
    }
    private static boolean contains(byte[] content, byte[] pattern) { outer: for (int i = 0; i <= content.length - pattern.length; i++) { for (int j = 0; j < pattern.length; j++) if (content[i + j] != pattern[j]) continue outer; return true; } return false; }
    private static String detect(byte[] content) {
        if (content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) return "image/png";
        if (content.length >= 3 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff) return "image/jpeg";
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-') return "application/pdf";
        return content.length < 52428800 ? "application/octet-stream" : null;
    }
}
