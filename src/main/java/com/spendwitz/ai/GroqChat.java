package com.spendwitz.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openai.errors.OpenAIServiceException;

import reactor.core.publisher.Flux;

/**
 * Fast chat through Groq (console.groq.com), set up like MediZon's GroqClient. GROQ_MODEL is a comma-separated list
 * tried in order: Groq's quota is per model, so a model whose quota ran out is skipped for 5 minutes and the next
 * answers. When Groq itself fails (down, unreachable, bad key) it's skipped for 2 minutes and chat goes to Gemini.
 * At most 4 requests run at once, to stay inside Groq's tokens per minute.
 */
@Component
public class GroqChat {

    private static final Logger log = LoggerFactory.getLogger(GroqChat.class);

    static final Duration COOL_DOWN = Duration.ofMinutes(2);
    static final Duration QUOTA_COOL_DOWN = Duration.ofMinutes(5);
    private static final int MAX_CONCURRENT = 4;
    private static final Duration SLOT_WAIT = Duration.ofSeconds(2);
    /** Most replies are a few sentences; the cap also bounds reasoning, so a chat can't eat the per-minute tokens. */
    static final int MAX_TOKENS = 1024;

    /**
     * Sent with every request. Reasoning models (gpt-oss) otherwise return their reasoning, Spring AI keeps it on the
     * assistant message and sends it back as reasoning_content on the next call (a tool round or the next question),
     * and Groq rejects that field with a 400.
     */
    public static final Map<String, Object> EXTRA_BODY = Map.of("include_reasoning", false);

    private final ChatClient client;
    private final List<String> models;
    private final Clock clock;
    private final Map<String, Instant> modelSkipUntil = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private volatile Instant skipUntil = Instant.EPOCH;

    public GroqChat(@Qualifier("fastChatClient") ObjectProvider<ChatClient> client,
            @Value("${spendwitz.ai.groq.model:}") String models, Clock clock) {
        this.client = client.getIfAvailable();
        this.models = Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
        this.clock = clock;
    }

    /** Has a key, isn't resting after a failure, and some model still has quota. */
    public boolean isAvailable() {
        return client != null && clock.instant().isAfter(skipUntil) && nextModel() != null;
    }

    /**
     * Streams the answer from the first model with quota left. Errors when Groq is busy, resting or out of quota on
     * every model, or when it fails before sending text; then the caller asks Gemini instead.
     */
    public Flux<String> stream(Function<ChatClient.ChatClientRequestSpec, Flux<String>> request) {
        return Flux.defer(() -> {
            if (!isAvailable()) {
                return Flux.error(new GroqUnavailableException("Groq is resting after a failure or out of quota"));
            }
            try {
                if (!slots.tryAcquire(SLOT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return Flux.error(new GroqUnavailableException("Groq is busy with other requests"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new GroqUnavailableException("Interrupted waiting for Groq"));
            }
            return fromNextModel(request).doFinally(signal -> slots.release());
        });
    }

    private Flux<String> fromNextModel(Function<ChatClient.ChatClientRequestSpec, Flux<String>> request) {
        String model = nextModel();
        if (model == null) {
            return Flux.error(new GroqUnavailableException("Groq quota used up on every model"));
        }
        AtomicBoolean started = new AtomicBoolean();
        return Flux.defer(() -> request.apply(client.prompt().options(optionsFor(model))))
                .doOnNext(chunk -> started.set(true))
                .onErrorResume(e -> {
                    if (started.get()) {
                        return Flux.error(e);
                    }
                    int status = statusOf(e);
                    if (status == 429) {
                        modelSkipUntil.put(model, clock.instant().plus(QUOTA_COOL_DOWN));
                        log.warn("Groq {} quota used up; trying the next model for {} minutes", model,
                                QUOTA_COOL_DOWN.toMinutes());
                        return fromNextModel(request);
                    }
                    if (status != 400) {
                        // Down, unreachable or misconfigured. A 400 is about this request (often a garbled tool
                        // call), not Groq, so the next message may well work.
                        skipUntil = clock.instant().plus(COOL_DOWN);
                        log.warn("Groq failed ({}); skipping it for {} minutes", e.toString(), COOL_DOWN.toMinutes());
                    }
                    return Flux.error(e);
                });
    }

    static OpenAiChatOptions.Builder optionsFor(String model) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .maxCompletionTokens(MAX_TOKENS)
                .extraBody(EXTRA_BODY);
        // Reasoning models think before answering; a little is plenty for chat and saves time and quota
        if (model.startsWith("openai/gpt-oss")) {
            options.reasoningEffort("low");
        }
        return options;
    }

    /** The first model whose quota isn't used up, or null. */
    private String nextModel() {
        Instant now = clock.instant();
        return models.stream()
                .filter(m -> now.isAfter(modelSkipUntil.getOrDefault(m, Instant.EPOCH)))
                .findFirst()
                .orElse(null);
    }

    /** The HTTP status Groq answered with, or 0 when it couldn't be reached at all. */
    private static int statusOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OpenAIServiceException service) {
                return service.statusCode();
            }
        }
        return 0;
    }

    public static class GroqUnavailableException extends RuntimeException {

        public GroqUnavailableException(String message) {
            super(message);
        }
    }
}
