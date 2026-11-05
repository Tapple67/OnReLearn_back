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
    private static final int MAX_GROUNDING_REPAIRS = 2;
    private static final int REPAIR_SPARES = 2;
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
        // ! No optional review may turn an invalid generation into a successful result.
        stage.accept("validating");
        if (reviewEnabled && matches(output, spec)) {
            stage.accept("reviewing");
            try {
                var notes = context.sourcePages().stream().filter(page -> !page.note().isBlank())
                        .map(page -> new QuizContext.Note(page.page(), page.note(), page.signals())).toList();
                JsonNode reviewed = parsed(critic, prompt.critique(output, notes), critiqueFormat,
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
        return new QuizResult(grounded(output, context, spec, focus, stage), spec, generator.provider(),
                reviewEnabled ? model + " (critic: " + criticModel + ")" : model, QuizPrompt.VERSION);
    }

    // * Verified items accumulate across requests, so a retry only pays for what is still missing.
    private JsonNode grounded(JsonNode output, QuizContext context, QuizSpec spec, QuizFocus focus, Consumer<String> stage) {
        JsonNode candidates = output;
        for (int repairs = 0; ; repairs++) {
            try {
                return GroundedQuiz.verify(candidates, context, spec);
            } catch (GroundedQuiz.Shortfall rejected) {
                if (repairs == MAX_GROUNDING_REPAIRS) throw rejected;
                stage.accept("grounding_retry");
                JsonNode repair = parsed(generator, prompt.groundingRepair(context, withSpares(rejected.missing()), focus,
                        rejected.guidance(), rejected.acceptedQuestions()), generationFormat, QuizOutputValidator::validateStructure);
                stage.accept("grounding");
                candidates = GroundedQuiz.combine(candidates, repair);
            }
        }
    }

    // * Asking for exactly what is missing fails again on one more rejection; verification drops any surplus.
    private static QuizSpec withSpares(QuizSpec missing) {
        return new QuizSpec(spared(missing.mcqCount()), spared(missing.tfCount()), spared(missing.shortCount()));
    }
    private static int spared(int count) { return count == 0 ? 0 : count + REPAIR_SPARES; }

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
