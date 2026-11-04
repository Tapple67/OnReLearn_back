package com.onrelearn.material.service;

public class MaterialPersistenceException extends RuntimeException {
    public MaterialPersistenceException(Throwable cause) {
        super("Material and extraction job could not be persisted", cause);
    }
}
