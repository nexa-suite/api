package com.nexa.api.invoicing.application.port;

public interface ContentScannerPort {
    ScanResult scan(byte[] content);
    record ScanResult(boolean clean, String detectedContentType, String reason) { }
}
