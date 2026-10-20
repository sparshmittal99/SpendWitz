package com.spendwitz.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Groq first (models in order, MediZon-style cool-downs), then Gemini. */
class ResilientChatTest {

    private static final String BIG = "openai/gpt-oss-120b";
    private static final String SMALL = "openai/gpt-oss-20b";

    private final TestClock clock = new TestClock();
    private final ChatClient gemini = mock(ChatClient.class);
    private final ChatClient groq = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec geminiPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec groqPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec bigPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec smallPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    /** How many requests each model got. */
    private final Map<ChatClient.ChatClientRequestSpec, Integer> calls = new ConcurrentHashMap<>();

    ResilientChatTest() {
        when(gemini.prompt()).thenReturn(geminiPrompt);
        when(groq.prompt()).thenReturn(groqPrompt);
        when(groqPrompt.options(argThat(b -> isModel(b, BIG)))).thenReturn(bigPrompt);
        when(groqPrompt.options(argThat(b -> isModel(b, SMALL)))).thenReturn(smallPrompt);
    }

    @Test
    void answersWithTheFirstGroqModelWhenItWorks() {
        var chat = chat(groq);
        assertThat(ask(chat, Map.of(bigPrompt, Flux.just("fast ", "answer")))).containsExactly("fast ", "answer");
        assertThat(calls).containsOnlyKeys(bigPrompt);
    }

    @Test
    void aModelOutOfQuotaIsSkippedForFiveMinutes() {
        var chat = chat(groq);
        var answers = Map.of(bigPrompt, Flux.<String>error(status(RateLimitException.class, 429)),
                smallPrompt, Flux.just("small answer"));

        assertThat(ask(chat, answers)).containsExactly("small answer");
        assertThat(ask(chat, answers)).containsExactly("small answer");
        assertThat(calls.get(bigPrompt)).isEqualTo(1);                   // not asked again while resting

        clock.advance(GroqChat.QUOTA_COOL_DOWN.plusSeconds(1));
        ask(chat, answers);
        assertThat(calls.get(bigPrompt)).isEqualTo(2);                   // tried again once the 5 minutes pass
    }

    @Test
    void whenEveryGroqModelIsOutOfQuotaGeminiAnswers() {
        var chat = chat(groq);
        var answers = Map.of(bigPrompt, Flux.<String>error(status(RateLimitException.class, 429)),
                smallPrompt, Flux.<String>error(status(RateLimitException.class, 429)),
                geminiPrompt, Flux.just("gemini answer"));

        assertThat(ask(chat, answers)).containsExactly("gemini answer");
        assertThat(ask(chat, answers)).containsExactly("gemini answer");
        assertThat(calls.get(bigPrompt)).isEqualTo(1);
        assertThat(calls.get(smallPrompt)).isEqualTo(1);
    }

    @Test
    void aGroqOutageSkipsGroqForTwoMinutes() {
        var chat = chat(groq);
        var answers = Map.of(bigPrompt, Flux.<String>error(status(InternalServerException.class, 503)),
                smallPrompt, Flux.just("small"), geminiPrompt, Flux.just("gemini answer"));

        assertThat(ask(chat, answers)).containsExactly("gemini answer");
        assertThat(ask(chat, answers)).containsExactly("gemini answer");
        assertThat(calls.get(bigPrompt)).isEqualTo(1);
        assertThat(calls).doesNotContainKey(smallPrompt);                // an outage isn't about one model's quota

        clock.advance(GroqChat.COOL_DOWN.plusSeconds(1));
        ask(chat, answers);
        assertThat(calls.get(bigPrompt)).isEqualTo(2);
    }

    @Test
    void aRejectedRequestGoesToGeminiWithoutRestingGroq() {
        var chat = chat(groq);
        var answers = Map.of(bigPrompt, Flux.<String>error(status(BadRequestException.class, 400)),
                geminiPrompt, Flux.just("gemini answer"));

        assertThat(ask(chat, answers)).containsExactly("gemini answer");
        ask(chat, answers);
        assertThat(calls.get(bigPrompt)).isEqualTo(2);                   // the next message tries Groq again
    }

    @Test
    void neverMixesTwoAnswersWhenGroqFailsHalfway() {
        var halfAnswer = Flux.just("You spent").concatWith(Flux.error(new IllegalStateException("connection reset")));
        List<String> received = new ArrayList<>();
        var answer = chat(groq).streamPreferFast(answers(Map.of(bigPrompt, halfAnswer, geminiPrompt, Flux.just("gemini"))))
                .doOnNext(received::add);
        assertThatThrownBy(answer::blockLast).hasMessageContaining("connection reset");
        assertThat(received).containsExactly("You spent");
    }

    @Test
    void atMostFourGroqRequestsRunAtOnce() {
        var chat = chat(groq);
        Sinks.Many<String> slow = Sinks.many().multicast().onBackpressureBuffer();
        var answers = Map.of(bigPrompt, slow.asFlux(), geminiPrompt, Flux.just("gemini answer"));
        for (int i = 0; i < 4; i++) {
            chat.streamPreferFast(answers(answers)).subscribe();
        }

        assertThat(ask(chat, answers)).containsExactly("gemini answer");  // the fifth waits 2 s, then Gemini
        slow.tryEmitComplete();
        assertThat(ask(chat, Map.of(bigPrompt, Flux.just("free again")))).containsExactly("free again");
    }

    @Test
    void usesGeminiWhenNoGroqKeyIsSet() {
        assertThat(ask(chat(null), Map.of(geminiPrompt, Flux.just("gemini answer")))).containsExactly("gemini answer");
    }

    @Test
    void gptOssModelsAreAskedToReasonBrieflyAndAnswerShortly() {
        OpenAiChatOptions options = GroqChat.optionsFor(BIG).build();
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(GroqChat.MAX_TOKENS);
        assertThat(options.getExtraBody()).containsEntry("include_reasoning", false);
        assertThat(GroqChat.optionsFor("llama-3.1-8b-instant").build().getReasoningEffort()).isNull();
    }

    @SuppressWarnings("unchecked")
    private ResilientChat chat(ChatClient fast) {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fast);
        return new ResilientChat(gemini, new GroqChat(provider, BIG + ", " + SMALL, clock), "");
    }

    private List<String> ask(ResilientChat chat, Map<ChatClient.ChatClientRequestSpec, Flux<String>> byPrompt) {
        return chat.streamPreferFast(answers(byPrompt)).collectList().block();
    }

    /** Answers differently depending on which model the request was sent to, and counts the requests. */
    private Function<ChatClient.ChatClientRequestSpec, Flux<String>> answers(
            Map<ChatClient.ChatClientRequestSpec, Flux<String>> byPrompt) {
        return prompt -> {
            assertThat(byPrompt).containsKey(prompt);
            calls.merge(prompt, 1, Integer::sum);
            return byPrompt.get(prompt);
        };
    }

    private static <T extends RuntimeException> T status(Class<T> type, int code) {
        T error = mock(type);
        if (error instanceof com.openai.errors.OpenAIServiceException service) {
            when(service.statusCode()).thenReturn(code);
        }
        return error;
    }

    /** Every Groq request turns reasoning off, or follow-up calls fail with a 400. */
    private static boolean isModel(Object builder, String model) {
        if (!(builder instanceof OpenAiChatOptions.Builder b)) {
            return false;
        }
        OpenAiChatOptions options = b.build();
        return model.equals(options.getModel())
                && Boolean.FALSE.equals(options.getExtraBody().get("include_reasoning"));
    }

    private static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
