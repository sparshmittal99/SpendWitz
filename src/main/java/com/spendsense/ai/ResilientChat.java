package com.spendsense.ai;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
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
    private final String fallbackModel;

    public ResilientChat(ChatClient chatClient, @Value("${spendsense.ai.fallback-model:}") String fallbackModel) {
        this.chatClient = chatClient;
        this.fallbackModel = fallbackModel;
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
