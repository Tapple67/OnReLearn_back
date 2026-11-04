package com.onrelearn.quiz.ai;
import com.onrelearn.quiz.model.QuizSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

public final class QuizPrompt {
    public static final String VERSION = "quiz_v12_memo_requests";
    // * Sets from these versions passed source checks, so the list does not ask to regenerate them.
    private static final java.util.Set<String> GROUNDED_VERSIONS = java.util.Set.of(VERSION, "quiz_v11_memo_aware", "quiz_v10_source_based", "quiz_v9_grounded_concepts");
    public static boolean grounded(String version) { return version != null && GROUNDED_VERSIONS.contains(version); }
    private final JsonMapper json = JsonMapper.builder().build();
    private final String template = load("generation");
    private final String general = load("general");
    private final String wrong = load("wrong");
    private final String weak = load("weak");

    public String render(QuizContext context, QuizSpec spec, QuizFocus focus) {
        String focusText = general;
        if (focus != null) {
            var data = json.createObjectNode();
            data.put("kind", focus.kind().name());
            data.set("topics", json.valueToTree(focus.topics()));
            if (focus.kind() == QuizFocus.Kind.WRONG_REVIEW)
                data.set("wrongItems", json.valueToTree(focus.wrongItems()));
            focusText = (focus.kind() == QuizFocus.Kind.WRONG_REVIEW ? wrong : weak).formatted(pretty(data));
        }
        // ! Insert arguments once: user text that resembles placeholders remains literal.
        return template.formatted(context.sourcePages().stream().map(QuizContext.SourcePage::page).toList(), memoLimit(spec),
                spec.mcqCount(), spec.tfCount(), spec.shortCount(),
                context.summary(), focusText, pretty(context.memoFocus()), pretty(context.sourcePages()));
    }
    public String critique(tools.jackson.databind.JsonNode quiz, java.util.List<QuizContext.Note> notes) {
        boolean hasNotes = notes.stream().anyMatch(note -> !note.note().isBlank());
        return load("critique").formatted(load(hasNotes ? "critique-notes" : "critique-no-notes"),
                pretty(quiz), pretty(notes));
    }
    public String repair(String basePrompt, tools.jackson.databind.JsonNode quiz, QuizCritique critique) {
        return load("repair").formatted(basePrompt, pretty(quiz), pretty(critique.output()));
    }
    public String jsonRepair(String base) {
        return base + "\nReturn one valid JSON object only.";
    }
    String groundingRepair(QuizContext context, QuizSpec missing, QuizFocus focus, java.util.List<String> guidance, java.util.List<String> acceptedQuestions) {
        return render(context, missing, focus)
                + "\n[이전 탈락 사유]\n" + String.join("\n", guidance)
                + "\n이미 검증된 문항은 서버가 보존한다. 위에 지정된 부족분만 생성한다. "
                + "다음 확정 문제와 같은 질문을 반복하지 않는다: " + pretty(acceptedQuestions);
    }
    // * A short quiz can still reflect a memo: at least one question, otherwise 30% rounded down.
    static int memoLimit(QuizSpec spec) { return (int) Math.max(1, spec.totalCount() * 3L / 10); }
    private String pretty(Object value) { return json.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
    private static String load(String part) {
        try (var stream = new ClassPathResource("prompts/quiz-v6-" + part + ".txt").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Quiz prompt unavailable");
        }
    }
}
