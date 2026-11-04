package com.onrelearn.quiz.ai;
import com.onrelearn.quiz.model.QuizSpec;

// * Generation and review model settings belong to the provider, not the caller.
public interface QuizProvider {
    QuizResult generate(QuizContext context, QuizSpec spec, QuizFocus focus);
    default QuizResult generate(QuizContext context, QuizSpec spec, QuizFocus focus, java.util.function.Consumer<String> stage) {
        stage.accept("generating"); return generate(context,spec,focus);
    }
}
