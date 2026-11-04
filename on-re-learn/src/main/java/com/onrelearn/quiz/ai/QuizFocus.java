package com.onrelearn.quiz.ai;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

// ! Only a service that checked ownership may supply prior attempt content.
public record QuizFocus(Kind kind, List<String> topics, List<WrongItem> wrongItems) {
    public enum Kind { WRONG_REVIEW, WEAK_TOPIC }
    public QuizFocus {
        Objects.requireNonNull(kind);
        topics = List.copyOf(topics);
        wrongItems = List.copyOf(wrongItems);
        if (kind == Kind.WEAK_TOPIC && !wrongItems.isEmpty())
            throw new IllegalArgumentException("Weak topic focus cannot contain wrong items");
    }
    public record WrongItem(String question, String topic, String explanation, JsonNode evidence) {
        public WrongItem {
            Objects.requireNonNull(question);
            Objects.requireNonNull(topic);
            evidence = evidence == null ? null : evidence.deepCopy();
        }
        @Override public JsonNode evidence() { return evidence == null ? null : evidence.deepCopy(); }
        @Override public String toString() { return "WrongItem[content omitted]"; }
    }
    @Override public String toString() { return "QuizFocus[content omitted]"; }
}
