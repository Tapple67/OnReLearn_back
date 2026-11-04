package com.onrelearn.material.service;

// * HTTP API가 아닌 worker 경계에서 처리할 예외다. 소유권을 잃은 결과를 저장하지 않도록 중단한다.
public class ExtractionLeaseLostException extends RuntimeException {
    public ExtractionLeaseLostException() {
        super("Extraction job lease is no longer owned by this attempt");
    }
}
