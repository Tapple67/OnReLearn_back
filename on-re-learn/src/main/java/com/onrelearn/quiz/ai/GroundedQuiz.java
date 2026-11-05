package com.onrelearn.quiz.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.ai.AiResponseFailure;
import com.onrelearn.quiz.model.QuizSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

// * Questions and choices may reword the source but must stay on it; explanations always show the original paragraph.
public final class GroundedQuiz {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundedQuiz.class);
    private enum Rejection {
        INVALID_EVIDENCE("evidence 형식이 잘못됐다"),
        UNKNOWN_PAGE("제공되지 않은 페이지를 인용했다. 지정된 페이지 목록에서만 인용한다"),
        QUOTE_MISMATCH("인용구를 원문에서 찾지 못했다. pdfText의 문장을 그대로 복사한다"),
        MEMO_LIMIT("메모 연결 문항 상한을 넘었다. 메모와 연결되지 않은 원문 개념으로 출제한다"),
        ANSWER_MISMATCH("정답이 인용 페이지 내용과 연결되지 않았다. 인용 페이지에 근거가 있는 정답을 쓴다"),
        INVALID_CHOICE("보기가 중복이거나 자료와 무관했다. 자료에 나오는 개념으로 서로 다른 보기를 쓴다"),
        DUPLICATE_QUESTION("같은 문제를 반복했다. 다른 개념이나 판단을 묻는다"),
        QUESTION_FORMAT("문제에 페이지 표시나 빈칸을 넣었다. 위치를 언급하지 말고 개념을 묻는다"),
        INVALID_QUESTION("문제가 인용 문단과 무관했다. 인용 문단에 나오는 용어로 그 개념을 묻는다");

        private final String guidance;
        Rejection(String guidance) { this.guidance = guidance; }
    }
    private record Located(int page, String pdfText, String paragraph) {}

    static final class Shortfall extends AiResponseFailure {
        private final QuizSpec missing;
        private final List<String> acceptedQuestions;
        private final List<String> guidance;

        private Shortfall(QuizSpec missing, Set<String> acceptedQuestions, List<String> guidance) {
            super(Code.QUIZ_GROUNDING, null);
            this.missing = missing;
            this.acceptedQuestions = List.copyOf(acceptedQuestions);
            this.guidance = List.copyOf(guidance);
        }

        QuizSpec missing() { return missing; }
        List<String> acceptedQuestions() { return acceptedQuestions; }
        List<String> guidance() { return guidance; }
    }

    private static void reject(Map<String, Map<Rejection, Integer>> rejected, String type, Rejection reason) {
        rejected.computeIfAbsent(type, key -> new EnumMap<>(Rejection.class)).merge(reason, 1, Integer::sum);
    }
    private static List<String> guidance(Map<String, Map<Rejection, Integer>> rejected) {
        var lines = new ArrayList<String>();
        rejected.forEach((type, reasons) -> reasons.forEach((reason, count) ->
                lines.add(type + " " + count + "건: " + reason.guidance)));
        return lines;
    }
    private GroundedQuiz() {}
    public static JsonNode verify(JsonNode generated, QuizContext context, QuizSpec spec) {
        // * Counts are settled below: surplus items are dropped and shortages go back to the model.
        QuizOutputValidator.validateStructure(generated);
        return selectVerified(generated, context, spec);
    }

    static JsonNode combine(JsonNode original, JsonNode repair) {
        ObjectNode combined = (ObjectNode) original.deepCopy();
        var items = (tools.jackson.databind.node.ArrayNode) combined.path("items");
        repair.path("items").forEach(items::add);
        return combined;
    }

    // * The server shows the page that actually holds the quote, even when the model cited another one.
    private static Located locate(QuizContext context, int page, String quote) {
        var pages = new ArrayList<>(context.sourcePages());
        pages.sort(Comparator.comparing(candidate -> candidate.page() != page));
        for (var candidate : pages) {
            String passage = SourceEvidence.passage(candidate.pdfText(), quote);
            if (passage != null) return new Located(candidate.page(), candidate.pdfText(), passage);
        }
        for (var candidate : pages) {
            String similar = SourceEvidence.similarParagraph(candidate.pdfText(), quote);
            if (similar != null) return new Located(candidate.page(), candidate.pdfText(), similar);
        }
        return null;
    }

    private static JsonNode selectVerified(JsonNode generated, QuizContext context, QuizSpec spec) {
        ObjectNode output=(ObjectNode)generated.deepCopy();
        output.put("title","원문 근거 퀴즈");
        var verified=output.arrayNode();
        int memoCount = 0;
        var rejected = new TreeMap<String, Map<Rejection, Integer>>();
        var questions = new LinkedHashSet<String>();
        String allSources = String.join("\n", context.sourcePages().stream().map(QuizContext.SourcePage::pdfText).toList());
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
            int cited=evidence.path("page").asInt();
            var located=locate(context,cited,evidence.path("quote").asString());
            if (located==null) {
                boolean known=context.sourcePages().stream().anyMatch(candidate -> candidate.page()==cited);
                reject(rejected, type, known ? Rejection.QUOTE_MISMATCH : Rejection.UNKNOWN_PAGE);
                continue;
            }
            int page=located.page();
            String paragraph=located.paragraph();
            // * Judge what the item asks and quotes: the paragraph can be a whole page, which would count every item on it.
            String asked = item.path("question").asString() + " " + evidence.path("quote").asString();
            var matched = context.memoFocus().stream().filter(focus -> focus.matches(page, asked)).toList();
            if (!matched.isEmpty() && memoCount >= QuizPrompt.memoLimit(spec)) {
                reject(rejected, type, Rejection.MEMO_LIMIT);
                continue;
            }
            String question = SourceEvidence.normalize(item.path("question").asString());
            if (question.contains("[빈칸]") || question.contains("원문 페이지 대조")
                    || question.matches(".*(?:p\\.\\s*\\d+|\\d+\\s*쪽).*")) {
                reject(rejected, type, Rejection.QUESTION_FORMAT);
                continue;
            }
            if (!SourceEvidence.related(question, paragraph)) {
                reject(rejected, type, Rejection.INVALID_QUESTION);
                continue;
            }
            if (type.equals("mcq")) {
                String answer = item.path("choices").get(item.path("answerKey").path("correctIndex").intValue()).asString();
                if (!SourceEvidence.supports(answer, located.pdfText())) {
                    reject(rejected, type, Rejection.ANSWER_MISMATCH);
                    continue;
                }
                var distinct = new HashSet<String>();
                boolean related = true;
                for (var choice : item.path("choices")) {
                    distinct.add(SourceEvidence.compact(choice.asString()));
                    related &= SourceEvidence.related(choice.asString(), allSources);
                }
                if (!related || distinct.contains("") || distinct.size() != item.path("choices").size()) {
                    reject(rejected, type, Rejection.INVALID_CHOICE);
                    continue;
                }
            } else if (type.equals("short")) {
                // ! Grading accepts only aliases the source supports.
                var supported = new ArrayList<String>();
                for (var accepted : item.path("answerKey").path("accepted")) {
                    if (SourceEvidence.supports(accepted.asString(), located.pdfText())) supported.add(SourceEvidence.normalize(accepted.asString()));
                }
                if (supported.isEmpty()) {
                    reject(rejected, type, Rejection.ANSWER_MISMATCH);
                    continue;
                }
                var accepted = item.putObject("answerKey").putArray("accepted");
                supported.forEach(accepted::add);
            }
            item.put("question", question);
            item.put("explanation", "원문 근거 (p." + page + "):\n" + paragraph);
            item.put("topic","p."+page+" 원문 확인");
            item.putObject("evidence").put("source","pdf").put("page",page).put("quote",paragraph);
            // * Record only server-verified memo concepts, never the model's own claim.
            var hits = item.putArray("signalHits");
            matched.stream().flatMap(focus -> focus.concepts().stream()).distinct().sorted().forEach(hits::add);
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
            throw new Shortfall(new QuizSpec(remaining.get("mcq"), remaining.get("tf"), remaining.get("short")), questions, guidance(rejected));
        }
        QuizOutputValidator.validate(output,spec);
        return output;
    }

}
