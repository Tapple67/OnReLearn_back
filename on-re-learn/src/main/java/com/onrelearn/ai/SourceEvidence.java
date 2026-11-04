package com.onrelearn.ai;

import java.text.Normalizer;

// * Match only whitespace-normalized, case-sensitive source text. Never fuzzy-match invented citations.
public final class SourceEvidence {
    private SourceEvidence() {}
    public static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC)
                .replaceAll("[\\s\\p{Zs}\\uFEFF]+", " ").strip();
    }
    public static String paragraph(String source, String quote) {
        String citation=normalize(quote);
        if (citation.length()<12) return null;
        // ! Return the whole enclosing paragraph, preserving negation/context around the selected quote.
        for (String paragraph : source.split("\\R\\s*\\R")) {
            String original=normalize(paragraph);
            if (original.contains(citation)) return original;
        }
        return null;
    }
}
