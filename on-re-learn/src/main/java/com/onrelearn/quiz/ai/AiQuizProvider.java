package com.onrelearn.quiz.ai;

import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.ai.AiTextClient;
import com.onrelearn.ai.AiResponseFailure;
import java.io.IOException;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class AiQuizProvider implements QuizProvider {
    private final AiTextClient generator, critic;
    private final String model, criticModel;
    private final boolean reviewEnabled;
    private final QuizPrompt prompt = new QuizPrompt();
    private final JsonMapper json = JsonMapper.builder().build();
    private final JsonNode generationFormat = format("generation"), critiqueFormat = format("critique");

    public AiQuizProvider(AiTextClient generator, String model,
            AiTextClient critic, String criticModel, boolean reviewEnabled) {
        this.generator = generator; this.model = model;
        this.critic = critic; this.criticModel = criticModel; this.reviewEnabled = reviewEnabled;
    }

    @Override public QuizResult generate(QuizContext context, QuizSpec spec, QuizFocus focus) {
        return generate(context,spec,focus,stage -> {});
    }
    @Override public QuizResult generate(QuizContext context, QuizSpec spec, QuizFocus focus, java.util.function.Consumer<String> stage) {
        stage.accept("generating");
        String base = prompt.render(context, spec, focus);
        JsonNode output = parsed(generator, base, generationFormat, QuizOutputValidator::validateStructure);
        if (!matches(output, spec)) {
            output = parsed(generator, prompt.countRepair(base, spec),
                    generationFormat, QuizOutputValidator::validateStructure);
        }
        // ! No optional review may turn an invalid generation into a successful result.
        stage.accept("validating");
        QuizOutputValidator.validate(output, spec);
        if (reviewEnabled) {
            stage.accept("reviewing");
            try {
                JsonNode reviewed = parsed(critic, prompt.critique(output, java.util.List.of()), critiqueFormat,
                        value -> new QuizCritique(value, spec.totalCount()));
                var critique = new QuizCritique(reviewed, spec.totalCount());
                if (critique.needsRepair()) {
                    JsonNode repaired = parsed(generator, prompt.repair(base, output, critique),
                            generationFormat, value -> QuizOutputValidator.validate(value, spec));
                    output = repaired;
                }
            } catch (ResponseStatusException exception) {
                // * Optional review failures preserve the validated generation; programming errors still propagate.
                int status = exception.getStatusCode().value();
                if (Thread.currentThread().isInterrupted() || (status != 502 && status != 503 && status != 504)) throw exception;
            }
        }
        stage.accept("grounding");
        try {
            output = GroundedQuiz.verify(output, context, spec);
        } catch (GroundedQuiz.Shortfall rejected) {
            QuizSpec missing = rejected.missing();
            stage.accept("grounding_retry");
            JsonNode repair = parsed(generator, prompt.render(context, missing, focus)
                    + "\n이미 검증된 문항은 서버가 보존한다. 위에 지정된 부족분만 생성한다. "
                    + "메모 연결 목록에 없는 주제를 우선하고 정답과 보기, 인용구를 PDF에서 그대로 복사한다. "
                    + "다음 확정 문제와 같은 질문을 반복하지 않는다: "
                    + json.writeValueAsString(rejected.acceptedQuestions()),
                    generationFormat, value -> QuizOutputValidator.validate(value, missing));
            stage.accept("grounding");
            output = GroundedQuiz.verifyWithRepair(output, repair, context, spec);
        }
        return new QuizResult(output, spec, generator.provider(),
                reviewEnabled ? model + " (critic: " + criticModel + ")" : model, QuizPrompt.VERSION);
    }

    private JsonNode parsed(AiTextClient client, String text, JsonNode format, Consumer<JsonNode> validate) {
        for (int attempt = 0; ; attempt++) {
            // * Transport errors/refusal are not JSON repair triggers.
            String raw = client.request(attempt == 0 ? text : prompt.jsonRepair(text), format);
            JsonNode value;
            try {
                int first = raw.indexOf('{'), last = raw.lastIndexOf('}');
                value = json.readTree(first >= 0 && last >= first ? raw.substring(first, last + 1) : raw);
            } catch (JacksonException exception) {
                if (attempt == 0) continue;
                throw new AiResponseFailure(AiResponseFailure.Code.QUIZ_JSON, null);
            }
            try { validate.accept(value); return value; }
            catch (ResponseStatusException exception) {
                if (attempt == 0 && exception.getStatusCode().value() == 502) continue;
                if (exception.getStatusCode().value() != 502) throw exception;
                throw new AiResponseFailure(AiResponseFailure.Code.QUIZ_STRUCTURE, null);
            }
        }
    }
    private boolean matches(JsonNode output, QuizSpec spec) {
        try { QuizOutputValidator.validate(output, spec); return true; }
        catch (ResponseStatusException exception) { return false; }
    }
    private JsonNode format(String type) {
        try (var stream = new ClassPathResource("prompts/quiz-" + type + "-format.json").getInputStream()) {
            return json.readTree(stream);
        } catch (IOException exception) { throw new IllegalStateException("Quiz schema unavailable"); }
    }
}
