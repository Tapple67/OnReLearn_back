package com.onrelearn.quiz.service;

import com.onrelearn.quiz.ai.QuizFocus;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

// * Keep the legacy Number(page) contract without changing the stored evidence.
public final class QuizEvidencePages {
    private QuizEvidencePages() {}

    public static List<Double> from(QuizFocus focus) {
        return focus.wrongItems().stream().map(item -> page(item.evidence()))
                .filter(Objects::nonNull).toList();
    }

    public static Double page(JsonNode evidence) {
        if (evidence == null || !evidence.isObject()) return null;
        var value = evidence.get("page");
        double page;
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) page = value.doubleValue();
        else if (value.isBoolean()) page = value.booleanValue() ? 1 : 0;
        else {
            String text = stringValue(value).replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
            try {
                if (text.matches("0[xX][0-9a-fA-F]+")) page = new BigInteger(text.substring(2), 16).doubleValue();
                else if (text.matches("0[bB][01]+")) page = new BigInteger(text.substring(2), 2).doubleValue();
                else if (text.matches("0[oO][0-7]+")) page = new BigInteger(text.substring(2), 8).doubleValue();
                else if (text.matches("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                    page = Double.parseDouble(text);
                else return null;
            } catch (NumberFormatException invalid) { return null; }
        }
        return Double.isFinite(page) && page > 0 && page == Math.floor(page) ? page : null;
    }

    private static String stringValue(JsonNode value) {
        if (value.isString()) return value.stringValue();
        if (value.isArray()) {
            // * JavaScript arrays convert via join, including nested single-element arrays.
            return value.valueStream().map(v -> v.isNull() ? "" : stringValue(v))
                    .collect(Collectors.joining(","));
        }
        return value.toString();
    }
}
