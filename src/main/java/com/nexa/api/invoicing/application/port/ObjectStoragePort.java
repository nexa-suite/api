package com.nexa.api.invoicing.application.port;

import java.io.InputStream;
import java.io.ByteArrayInputStream;

public interface ObjectStoragePort {
    StoredObject put(String objectKey, InputStream content, long contentLength, String contentType);
    default StoredObject put(String objectKey, byte[] content, String contentType) {
        if (content == null) throw new IllegalArgumentException("Object content is required");
        return put(objectKey, new ByteArrayInputStream(content), content.length, contentType);
    }
    InputStream open(String objectKey);
    void delete(String objectKey);
    record StoredObject(String objectKey, String checksumSha256, String contentType, long byteSize) { }
}
