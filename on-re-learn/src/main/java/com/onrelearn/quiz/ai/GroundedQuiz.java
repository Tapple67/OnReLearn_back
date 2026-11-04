package com.onrelearn.quiz.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.ai.AiResponseFailure;
import com.onrelearn.quiz.model.QuizSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

// * Conservative source-reading exercises: the server verifies citations and retains the generated concept question.
public final class GroundedQuiz {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundedQuiz.class);
    private enum Rejection { INVALID_EVIDENCE, UNKNOWN_PAGE, QUOTE_MISMATCH, MEMO_LIMIT, ANSWER_MISMATCH, INVALID_CHOICE, DUPLICATE_QUESTION, INVALID_QUESTION }

    static final class Shortfall extends AiResponseFailure {
        private final QuizSpec missing;
        private final List<String> acceptedQuestions;

        private Shortfall(QuizSpec missing, Set<String> acceptedQuestions) {
            super(Code.QUIZ_GROUNDING, null);
            this.missing = missing;
            this.acceptedQuestions = List.copyOf(acceptedQuestions);
        }

        QuizSpec missing() { return missing; }
        List<String> acceptedQuestions() { return acceptedQuestions; }
    }

    private static void reject(Map<String, Integer> rejected, String type, Rejection reason) {
        rejected.merge(type + "." + reason.name(), 1, Integer::sum);
    }
    private GroundedQuiz() {}
    public static JsonNode verify(JsonNode generated, QuizContext context, QuizSpec spec) {
        QuizOutputValidator.validate(generated,spec);
        return selectVerified(generated, context, spec);
    }

    static JsonNode verifyWithRepair(JsonNode original, JsonNode repair, QuizContext context, QuizSpec spec) {
        ObjectNode combined = (ObjectNode) original.deepCopy();
        var items = (tools.jackson.databind.node.ArrayNode) combined.path("items");
        repair.path("items").forEach(items::add);
        return selectVerified(combined, context, spec);
    }

    private static JsonNode selectVerified(JsonNode generated, QuizContext context, QuizSpec spec) {
        ObjectNode output=(ObjectNode)generated.deepCopy();
        output.put("title","원문 근거 퀴즈");
        var verified=output.arrayNode();
        int memoCount = 0;
        var rejected = new TreeMap<String, Integer>();
        var questions = new LinkedHashSet<String>();
        var remaining = new HashMap<>(Map.of("mcq", spec.mcqCount(), "tf", spec.tfCount(), "short", spec.shortCount()));
        for (var raw:output.path("items")) {
            ObjectNode item=(ObjectNode)raw;
            String type=item.path("type").asString();
            if (remaining.get(type) == 0) continue;
            var evidence=item.path("evidence");
            if (!evidence.path("page").isIntegralNumber() || !evidence.path("page").canConvertToInt() || !evidence.path("quote").isString()) {
                reject(rejected, type, Rejection.INVALID_EVIDENCE);
                continue;
            }
            int page=evidence.path("page").asInt();
            var source=context.sourcePages().stream().filter(p -> p.page()==page).findFirst().orElse(null);
            if (source==null) {
                reject(rejected, type, Rejection.UNKNOWN_PAGE);
                continue;
            }
            String paragraph=SourceEvidence.paragraph(source.pdfText(),evidence.path("quote").asString());
            if (paragraph==null) {
                reject(rejected, type, Rejection.QUOTE_MISMATCH);
                continue;
            }
            var matched = context.memoFocus().stream().filter(focus -> focus.matches(page, paragraph)).toList();
            if (!matched.isEmpty() && memoCount >= QuizPrompt.memoLimit(spec)) {
                reject(rejected, type, Rejection.MEMO_LIMIT);
                continue;
            }
            String question = SourceEvidence.normalize(item.path("question").asString());
            if (question.contains("[빈칸]") || question.contains("원문 페이지 대조")
                    || question.matches(".*(?:p\\.\\s*\\d+|\\d+\\s*쪽).*")) {
                reject(rejected, type, Rejection.INVALID_QUESTION);
                continue;
            }
            if (type.equals("mcq") || type.equals("short")) {
                var answerKey = item.path("answerKey");
                String answer = type.equals("mcq") ? item.path("choices").get(answerKey.path("correctIndex").intValue()).asString()
                        : answerKey.path("accepted").get(0).asString();
                answer = SourceEvidence.normalize(answer);
                if (answer.isEmpty() || !paragraph.contains(answer)) {
                    reject(rejected, type, Rejection.ANSWER_MISMATCH);
                    continue;
                }
                if (type.equals("mcq")) {
                    var distinct = new HashSet<String>();
                    for (var choice : item.path("choices")) distinct.add(SourceEvidence.normalize(choice.asString()));
                    if (distinct.contains("") || distinct.size() != item.path("choices").size()
                            || distinct.stream().anyMatch(value -> context.sourcePages().stream()
                                    .noneMatch(candidate -> SourceEvidence.normalize(candidate.pdfText()).contains(value)))) {
                        reject(rejected, type, Rejection.INVALID_CHOICE);
                        continue;
                    }
                } else item.putObject("answerKey").putArray("accepted").add(answer);
            }
            item.put("question", question);
            item.put("explanation", "원문 근거 (p." + page + "):\n" + paragraph);
            item.put("topic","p."+page+" 원문 확인");
            item.putObject("evidence").put("source","pdf").put("page",page).put("quote",paragraph);
            item.putArray("signalHits");
            if (!questions.add(SourceEvidence.normalize(item.path("question").asString()))) {
                reject(rejected, type, Rejection.DUPLICATE_QUESTION);
                continue;
            }
            if (!matched.isEmpty()) memoCount++;
            verified.add(item);
            remaining.compute(type, (key, count) -> count - 1);
        }
        output.set("items",verified);
        // ! Never silently fill missing counts with unsupported items or save a smaller set than requested.
        if (remaining.values().stream().anyMatch(count -> count > 0)) {
            LOG.warn("Quiz grounding shortfall: missing={}, rejected={}", remaining, rejected);
            throw new Shortfall(new QuizSpec(remaining.get("mcq"), remaining.get("tf"), remaining.get("short")), questions);
        }
        QuizOutputValidator.validate(output,spec);
        return output;
    }

}
