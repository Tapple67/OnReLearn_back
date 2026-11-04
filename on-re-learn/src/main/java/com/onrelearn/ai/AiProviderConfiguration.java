package com.onrelearn.ai;

import com.onrelearn.quiz.ai.*;
import com.onrelearn.summary.ai.*;
import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class AiProviderConfiguration {
    @Bean
    public SummaryProvider summaryProvider(Environment env) {
        var settings = settings(env, "summary", "gpt-5-mini");
        var streaming = new StreamingSummaryProvider(settings.provider, settings.endpoint, settings.key, settings.model, settings.reasoning, settings.timeout,
                env.getProperty("onrelearn.ai.summary.batch-pages", Integer.class, 8),
                env.getProperty("onrelearn.ai.summary.batch-chars", Integer.class, 16000),
                env.getProperty("onrelearn.ai.summary.thinking-level", ""),
                env.getProperty("onrelearn.ai.summary.concurrency", Integer.class, 3),
                env.getProperty("onrelearn.ai.summary.goals", Boolean.class, true));
        return streaming;
    }

    @Bean
    public QuizProvider quizProvider(Environment env) {
        var generator = settings(env, "quiz", "gpt-5.2");
        var critic = settings(env, "quiz-critic", "gpt-5-mini");
        return new AiQuizProvider(client(generator), generator.model, client(critic), critic.model,
                env.getProperty("onrelearn.ai.quiz-critic.enabled", Boolean.class, false));
    }

    private AiTextClient client(Settings settings) {
        if (settings.provider.equals("gemini")) {
            return new GeminiClient(settings.endpoint, settings.key, settings.model,
                    settings.timeout, settings.retries, settings.retryDelay);
        }
        return new OpenAiQuizClient(settings.endpoint, settings.key, settings.model, settings.reasoning,
                settings.timeout, settings.retries, settings.retryDelay);
    }

    private Settings settings(Environment env, String purpose, String openAiModel) {
        String prefix = "onrelearn.ai." + purpose;
        String provider = value(env, prefix + ".provider", "openai").toLowerCase(Locale.ROOT);
        if (!provider.equals("openai") && !provider.equals("gemini")) {
            throw new IllegalArgumentException(prefix + ".provider must be openai or gemini");
        }
        String endpoint = provider.equals("gemini")
                ? "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
                : "https://api.openai.com/v1/responses";
        // * Gemini models are explicit: changing providers must not silently send an OpenAI model/key.
        return new Settings(provider, value(env, prefix + ".endpoint", endpoint),
                value(env, prefix + ".api-key", env.getProperty("onrelearn.ai." + provider + ".api-key", "")),
                value(env, prefix + ".model", provider.equals("openai") ? openAiModel : ""),
                env.getProperty(prefix + ".reasoning-effort", "low"),
                Duration.parse(env.getProperty(prefix + ".timeout", "PT60S")),
                env.getProperty(prefix + ".retries", Integer.class, 0),
                Duration.parse(env.getProperty(prefix + ".retry-delay", "PT1S")));
    }

    private String value(Environment env, String property, String fallback) {
        String value = env.getProperty(property);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private record Settings(String provider, String endpoint, String key, String model, String reasoning,
                            Duration timeout, int retries, Duration retryDelay) {
        // ! Configuration records must never expose credentials through generated toString.
        @Override public String toString() { return "AiSettings[values omitted]"; }
    }
}
