package com.nexa.api.invoicing.application.port;

import java.io.InputStream;

public interface ObjectStoragePort {
    StoredObject put(String objectKey, byte[] content, String contentType);
    InputStream open(String objectKey);
    void delete(String objectKey);
    record StoredObject(String objectKey, String checksumSha256, String contentType, long byteSize) { }
}
