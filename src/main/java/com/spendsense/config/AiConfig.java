package com.spendsense.config;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.spendsense.ai.CachingEmbeddingModel;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
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
