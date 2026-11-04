package com.onrelearn.material.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PdfTextExtractor {
    private final int maxPages;
    private final int maxCharsPerPage;

    public PdfTextExtractor(
        @Value("${onrelearn.material.extraction.max-pages:300}") int maxPages,
        @Value("${onrelearn.material.extraction.max-chars-per-page:100000}") int maxCharsPerPage) {
        if (maxPages < 1 || maxCharsPerPage < 1) throw new IllegalArgumentException("Invalid PDF extraction limits");
        this.maxPages = maxPages;
        this.maxCharsPerPage = maxCharsPerPage;
    }

    // * 다운로드된 PDF를 페이지 순서대로 읽는다. 호출자는 결과 저장 전에 lease를 다시 검사한다.
    public List<PageText> extract(byte[] pdf) {
        if (pdf == null || pdf.length < 5) throw new PdfExtractionException();
        try (var document = Loader.loadPDF(pdf)) {
            int count = document.getNumberOfPages();
            // ! 페이지 상한과 페이지별 텍스트 상한으로 과도한 결과 저장을 막는다.
            if (count < 1 || count > maxPages) throw new PdfExtractionException();
            var stripper = new PDFTextStripper();
            var pages = new ArrayList<PageText>(count);
            for (int page = 1; page <= count; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text.length() > maxCharsPerPage) throw new PdfExtractionException();
                pages.add(new PageText(page, text));
            }
            return List.copyOf(pages);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof PdfExtractionException known) throw known;
            throw new PdfExtractionException(failure);
        }
    }

    public record PageText(int page, String text) {}
}
