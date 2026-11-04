package com.onrelearn.material.service;

// * worker 처리 상한에 도달했을 때 현재 시도만 안전하게 실패 처리하기 위한 내부 예외다.
public class ExtractionProcessingTimeoutException extends RuntimeException {
    public ExtractionProcessingTimeoutException() {
        super("PDF extraction processing time exceeded");
    }
}
