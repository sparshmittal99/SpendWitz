package com.spendwitz.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.spendwitz.ai.CachingEmbeddingModel;
import com.spendwitz.ai.GroqChat;

import io.micrometer.observation.ObservationRegistry;

@Configuration
public class AiConfig {

    @Bean
    @Primary
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Optional fast chat for the assistant: Groq, through its OpenAI-compatible API. Only created when
     * GROQ_API_KEY is set. Built by hand (no starter) so Gemini stays the only auto-configured chat model.
     */
    @Bean
    @ConditionalOnExpression("!'${spendwitz.ai.groq.api-key:}'.isBlank()")
    ChatClient fastChatClient(@Value("${spendwitz.ai.groq.api-key}") String apiKey,
            @Value("${spendwitz.ai.groq.base-url}") String baseUrl, @Value("${spendwitz.ai.groq.model}") String models,
            ToolCallingManager toolCallingManager, ObjectProvider<ObservationRegistry> observationRegistry) {
        OpenAiChatModel groq = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        // GroqChat picks the model per request from the list; the first is the default
                        .model(models.split(",")[0].trim())
                        .extraBody(GroqChat.EXTRA_BODY)
                        .temperature(0.2)
                        // Fail fast so ResilientChat can hand the question to Gemini instead
                        .timeout(Duration.ofSeconds(20))
                        .maxRetries(0)
                        .build())
                .build();
        // Same wiring as the auto-configured Gemini client: tools run through the ChatClient's tool-calling advisor
        return ChatClient.builder(groq, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager))
                .build();
    }

    /** Every embedding goes through a Postgres cache, so repeated texts never cost an API call. */
    @Bean
    @Primary
    EmbeddingModel cachingEmbeddingModel(GoogleGenAiTextEmbeddingModel gemini, JdbcTemplate jdbc,
            @Value("${spring.ai.google.genai.embedding.text.model}") String model,
            @Value("${spring.ai.google.genai.embedding.text.dimensions}") int dimensions) {
        return new CachingEmbeddingModel(gemini, jdbc, model + "/" + dimensions);
    }

    /**
     * When a tool rejects its arguments (e.g. a malformed date), send the reason back to the model as JSON so it can
     * correct itself and retry. Gemini requires tool results to be JSON; plain text would fail the whole request.
     */
    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return exception -> {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            return JsonParser.toJson(Map.of("error", String.valueOf(cause.getMessage())));
        };
    }
}
