package com.onrelearn.material.service;

// * worker가 PDF 파싱 실패를 분류하고 내부 오류 내용을 사용자 응답에 노출하지 않도록 한다.
public class PdfExtractionException extends RuntimeException {
    public PdfExtractionException() { super("PDF extraction failed"); }
    public PdfExtractionException(Throwable cause) { super("PDF extraction failed", cause); }
}
