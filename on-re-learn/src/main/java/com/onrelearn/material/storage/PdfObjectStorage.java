package com.onrelearn.material.storage;

public interface PdfObjectStorage {
    void upload(String path, byte[] pdf);
    void delete(String path);
    String signedUrl(String path, long ttlSeconds);
    byte[] download(String path, long maxBytes);
}
