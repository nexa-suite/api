package com.nexa.api.invoicing.application.port;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public interface ContentScannerPort {
    ScanResult scan(InputStream content);
    default ScanResult scan(byte[] content) {
        if (content == null) return new ScanResult(false, null, "EMPTY_FILE");
        return scan(new ByteArrayInputStream(content));
    }
    record ScanResult(boolean clean, String detectedContentType, String reason) { }
}
