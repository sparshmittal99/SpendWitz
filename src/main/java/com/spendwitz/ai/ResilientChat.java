package com.spendwitz.ai;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.genai.errors.ApiException;

import reactor.core.publisher.Flux;

/**
 * Free-tier models are sometimes overloaded (HTTP 503) or rate-limited (429). When that happens the same request
 * is retried once on a lighter fallback model, so the live demo keeps working instead of showing an error.
 */
@Component
public class ResilientChat {

    private static final Logger log = LoggerFactory.getLogger(ResilientChat.class);

    private final ChatClient chatClient;
    private final ChatClient fastChatClient;
    private final List<String> fastModels;
    private final String fallbackModel;

    public ResilientChat(ChatClient chatClient, @Qualifier("fastChatClient") ObjectProvider<ChatClient> fastChatClient,
            @Value("${spendwitz.ai.groq.model:}") String fastModels,
            @Value("${spendwitz.ai.fallback-model:}") String fallbackModel) {
        this.chatClient = chatClient;
        this.fastChatClient = fastChatClient.getIfAvailable();
        this.fastModels = Arrays.stream(fastModels.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
        this.fallbackModel = fallbackModel;
    }

    /**
     * Streams from the fast chat models (Groq) when configured, trying each in order: Groq's free quota is per
     * model, so when one runs out the next answers. If they all fail before sending any text (rate limit, outage,
     * a malformed tool call), the same request goes to Gemini, with its own fallback model.
     */
    public Flux<String> streamPreferFast(Function<ChatClient.ChatClientRequestSpec, Flux<String>> request) {
        if (fastChatClient == null || fastModels.isEmpty()) {
            return stream(request);
        }
        return streamFast(request, 0);
    }

    private Flux<String> streamFast(Function<ChatClient.ChatClientRequestSpec, Flux<String>> request, int index) {
        if (index == fastModels.size()) {
            return stream(request);
        }
        String model = fastModels.get(index);
        AtomicBoolean started = new AtomicBoolean();
        return Flux.defer(() -> request.apply(fastChatClient.prompt().options(OpenAiChatOptions.builder().model(model))))
                .doOnNext(chunk -> started.set(true))
                .onErrorResume(e -> {
                    if (started.get()) {
                        return Flux.error(e);
                    }
                    String next = index + 1 < fastModels.size() ? fastModels.get(index + 1) : "Gemini";
                    log.info("Groq model {} failed ({}), trying {}", model, e.toString(), next);
                    return streamFast(request, index + 1);
                });
    }

    public <T> T call(Function<ChatClient.ChatClientRequestSpec, T> request) {
        try {
            return request.apply(chatClient.prompt());
        } catch (RuntimeException e) {
            if (!shouldFallBack(e)) {
                throw e;
            }
            log.info("Primary model unavailable, retrying with {}", fallbackModel);
            return request.apply(fallbackPrompt());
        }
    }

    /** Falls back only if nothing was streamed yet, so the user never sees a half answer followed by a second one. */
    public Flux<String> stream(Function<ChatClient.ChatClientRequestSpec, Flux<String>> request) {
        AtomicBoolean started = new AtomicBoolean();
        return Flux.defer(() -> request.apply(chatClient.prompt()))
                .doOnNext(chunk -> started.set(true))
                .onErrorResume(e -> {
                    if (started.get() || !shouldFallBack(e)) {
                        return Flux.error(e);
                    }
                    log.info("Primary model unavailable, streaming with {}", fallbackModel);
                    return request.apply(fallbackPrompt());
                });
    }

    private ChatClient.ChatClientRequestSpec fallbackPrompt() {
        return chatClient.prompt().options(GoogleGenAiChatOptions.builder().model(fallbackModel));
    }

    private boolean shouldFallBack(Throwable error) {
        if (fallbackModel.isBlank()) {
            return false;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) {
                return api.code() == 429 || api.code() >= 500;
            }
        }
        return false;
    }
}
