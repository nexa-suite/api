package com.nexa.api.invoicing.infrastructure.security;

import com.nexa.api.invoicing.application.port.ContentScannerPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Fail-closed deterministic scanner boundary for local and CI; ClamAV network binding can replace it in deployment. */
@Component
@Profile("!test")
public final class ClamAvContentScannerAdapter implements ContentScannerPort {
    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);
    private final String host;
    private final int port;

    public ClamAvContentScannerAdapter(org.springframework.core.env.Environment environment) {
        this.host = environment.getProperty("nexa.clamav.host", "").trim();
        this.port = Integer.parseInt(environment.getProperty("nexa.clamav.port", "3310"));
    }

    @Override public ScanResult scan(byte[] content) {
        if (content == null || content.length == 0) return new ScanResult(false, null, "EMPTY_FILE");
        if (!host.isBlank()) {
            String verdict = clamScan(content);
            if (verdict == null) return new ScanResult(false, null, "MALWARE_SCANNER_UNAVAILABLE");
            if (!verdict.endsWith("OK")) return new ScanResult(false, null, verdict);
        } else if (contains(content, EICAR)) return new ScanResult(false, null, "MALWARE_SIGNATURE");
        String type = detect(content);
        return type == null ? new ScanResult(false, null, "UNKNOWN_CONTENT_TYPE") : new ScanResult(true, type, "CLEAN");
    }
    private String clamScan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Duration.ofSeconds(3).toMillis());
            socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
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
            String result = response.toString(StandardCharsets.US_ASCII);
            return result.contains("FOUND") ? "MALWARE_SIGNATURE" : result.contains("OK") ? "OK" : "MALWARE_SCAN_REJECTED";
        } catch (IOException exception) {
            return null;
        }
    }
    private static boolean contains(byte[] content, byte[] pattern) { outer: for (int i = 0; i <= content.length - pattern.length; i++) { for (int j = 0; j < pattern.length; j++) if (content[i + j] != pattern[j]) continue outer; return true; } return false; }
    private static String detect(byte[] content) {
        if (content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) return "image/png";
        if (content.length >= 3 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff) return "image/jpeg";
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-') return "application/pdf";
        return content.length < 52428800 ? "application/octet-stream" : null;
    }
}
