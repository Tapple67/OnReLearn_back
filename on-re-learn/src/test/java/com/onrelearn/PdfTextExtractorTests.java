package com.onrelearn;

import com.onrelearn.material.service.PdfExtractionException;
import com.onrelearn.material.service.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PdfTextExtractorTests {
    private byte[] pdf(String... texts) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (String text : texts) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test void extractsTextInPageOrderAndPreservesBlankPages() throws Exception {
        var extractor = new PdfTextExtractor(3, 100);
        var pages = extractor.extract(pdf("first", "", "third"));
        assertEquals(3, pages.size());
        assertEquals(1, pages.get(0).page());
        assertTrue(pages.get(0).text().contains("first"));
        assertEquals(2, pages.get(1).page());
        assertTrue(pages.get(1).text().isBlank());
        assertEquals(3, pages.get(2).page());
        assertTrue(pages.get(2).text().contains("third"));
    }

    @Test void rejectsMalformedPdfAndConfiguredLimits() throws Exception {
        var extractor = new PdfTextExtractor(1, 5);
        assertThrows(PdfExtractionException.class, () -> extractor.extract("%PDF-broken".getBytes()));
        assertThrows(PdfExtractionException.class, () -> extractor.extract(pdf("one", "two")));
        assertThrows(PdfExtractionException.class, () -> extractor.extract(pdf("longer text")));
        assertThrows(IllegalArgumentException.class, () -> new PdfTextExtractor(0, 100));
    }
}
