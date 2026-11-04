package com.onrelearn.quiz.service;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

// * Pure grading only: ownership, attempt state and atomic persistence belong to the submit service.
public final class QuizGrading {
    private QuizGrading() {}
    // * Match JavaScript whitespace, including NBSP/BOM but excluding NEL.
    private static final String SPACE="[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
    private static final Pattern SPACES=Pattern.compile(SPACE+"+");
    private static final Pattern EDGES=Pattern.compile("^"+SPACE+"+|"+SPACE+"+$");

    public static Result grade(String type,Integer points,JsonNode key,JsonNode response) {
        int weight=points==null ? 1 : points;
        boolean correct=switch(type) {
            case "mcq" -> {
                var expected=field(key,"correctIndex");
                if(expected==null || !expected.isIntegralNumber()) throw invalidKey();
                var actual=field(response,"selectedIndex");
                yield actual!=null && actual.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())==0;
            }
            case "tf" -> {
                var expected=field(key,"correct");
                if(expected==null || !expected.isBoolean()) throw invalidKey();
                var actual=field(response,"value");
                yield actual!=null && actual.isBoolean() && actual.booleanValue()==expected.booleanValue();
            }
            case "short" -> shortCorrect(key,response);
            default -> false;
        };
        return new Result(correct,correct ? weight : 0);
    }
    private static boolean shortCorrect(JsonNode key,JsonNode response) {
        var accepted=field(key,"accepted");
        if(accepted==null || !accepted.isArray()) throw invalidKey();
        var actual=field(response,"text");
        String text=normalize(actual!=null && actual.isString() ? actual.stringValue() : "");
        Set<String> keywords=new HashSet<>();
        boolean exact=false;
        for(var answer:accepted) {
            if(!answer.isString()) throw invalidKey();
            String value=answer.stringValue();
            exact|=normalize(value).equals(text);
            keywords.addAll(tokens(value));
        }
        if(exact) return true;
        if(text.isEmpty() || keywords.isEmpty()) return false;
        Set<String> responseWords=tokens(text);
        long matches=keywords.stream().filter(responseWords::contains).count();
        // * Preserve the original union-of-accepted-answers heuristic, with no partial credit.
        return (double)matches/keywords.size()>=0.6 || matches>=2;
    }
    private static String normalize(String value) {
        return SPACES.matcher(EDGES.matcher(value).replaceAll("")).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
    private static Set<String> tokens(String value) {
        String cleaned=normalize(value).replaceAll("[^\\p{L}\\p{N} ]"," ");
        Set<String> result=new HashSet<>();
        for(String word:cleaned.split(" ")) if(word.length()>=2) result.add(word);
        return result;
    }
    private static JsonNode field(JsonNode node,String name) { return node!=null && node.isObject() ? node.get(name) : null; }
    // ! Malformed stored keys must not silently turn into a completed zero-score attempt.
    private static IllegalStateException invalidKey() { return new IllegalStateException("Invalid stored quiz answer key"); }
    public record Result(boolean isCorrect,int earned) {}
}
