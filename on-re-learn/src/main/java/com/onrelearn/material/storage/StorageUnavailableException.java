package com.onrelearn.material.storage;

public class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException() { super("PDF storage is unavailable"); }
    public StorageUnavailableException(Throwable cause) { super("PDF storage is unavailable", cause); }
}
