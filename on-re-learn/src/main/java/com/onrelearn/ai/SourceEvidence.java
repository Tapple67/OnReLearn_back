package com.onrelearn.ai;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

// * paragraph keeps exact matching for summaries; quiz checks allow rewording but still need source terms.
public final class SourceEvidence {
    private static final Pattern TERM=Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern ENDING=Pattern.compile("(?<=.{2})(으로|에서|에게|까지|부터|보다|처럼|은|는|이|가|을|를|에|의|와|과|도|만|로|하는|하다|한다|하여|해서|하고|합니다|된다|되는|됩니다|이다|입니다|한|할|함|된|됨)$");
    private static final Set<String> GENERIC=Set.of("다음","설명","무엇","어떤","어느","경우","내용","개념","방법","방식","문제","판단","질문","사용","위한","대한","관한","통해","있다","있는","없다","없는","없이","옳은","맞는","아닌","가장");
    private static final int MIN_SIMILAR_TERMS=4;
    private static final double MIN_SIMILAR_COVERAGE=0.8;
    private static final double MIN_ANSWER_COVERAGE=0.5;
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

    // * Letters and digits only: spacing and punctuation differences never change what a passage says.
    public static String compact(String text) {
        return normalize(text).replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase(Locale.ROOT);
    }

    // * Same letters as the source, possibly spanning a paragraph break; returns the original paragraphs.
    public static String passage(String source, String quote) {
        String citation=compact(quote);
        if (normalize(quote).length()<12) return null;
        var paragraphs=Arrays.stream(source.split("\\R\\s*\\R")).map(SourceEvidence::normalize).toList();
        for (int size=1; size<=paragraphs.size(); size++) {
            for (int start=0; start+size<=paragraphs.size(); start++) {
                String candidate=String.join(" ",paragraphs.subList(start,start+size));
                if (compact(candidate).contains(citation)) return candidate;
            }
        }
        return null;
    }

    // * A reworded quote still locates one paragraph when almost all of its terms come from it.
    public static String similarParagraph(String source, String quote) {
        var terms=terms(quote);
        if (terms.size()<MIN_SIMILAR_TERMS) return null;
        String best=null;
        double bestCoverage=MIN_SIMILAR_COVERAGE;
        for (String paragraph : source.split("\\R\\s*\\R")) {
            double coverage=coverage(terms,paragraph);
            if (coverage>=bestCoverage) { best=normalize(paragraph); bestCoverage=coverage; }
        }
        return best;
    }

    // ! Term overlap shows a claim comes from the source; it cannot prove the claim is true.
    public static boolean related(String claim, String source) {
        var terms=terms(claim);
        return terms.isEmpty() ? !compact(claim).isEmpty() && compact(source).contains(compact(claim)) : coverage(terms,source)>0;
    }

    public static boolean supports(String answer, String source) {
        String expression=compact(answer);
        if (expression.isEmpty()) return false;
        return compact(source).contains(expression) || coverage(terms(answer),source)>=MIN_ANSWER_COVERAGE;
    }

    private static double coverage(Set<String> terms, String source) {
        if (terms.isEmpty()) return 0;
        String text=compact(source);
        return (double) terms.stream().filter(text::contains).count()/terms.size();
    }

    private static Set<String> terms(String text) {
        var terms=new LinkedHashSet<String>();
        var words=TERM.matcher(normalize(text).toLowerCase(Locale.ROOT));
        while (words.find()) {
            String term=ENDING.matcher(words.group()).replaceFirst("");
            if (!GENERIC.contains(term)) terms.add(term);
        }
        return terms;
    }
}
