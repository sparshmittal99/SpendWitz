package com.spendwitz.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;

class ResilientChatTest {

    private static final String BIG = "openai/gpt-oss-120b";
    private static final String SMALL = "openai/gpt-oss-20b";

    private final ChatClient gemini = mock(ChatClient.class);
    private final ChatClient groq = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec geminiPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec groqPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec bigPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec smallPrompt = mock(ChatClient.ChatClientRequestSpec.class);

    ResilientChatTest() {
        when(gemini.prompt()).thenReturn(geminiPrompt);
        when(groq.prompt()).thenReturn(groqPrompt);
        when(groqPrompt.options(argThat(b -> isModel(b, BIG)))).thenReturn(bigPrompt);
        when(groqPrompt.options(argThat(b -> isModel(b, SMALL)))).thenReturn(smallPrompt);
    }

    @Test
    void answersWithTheFirstGroqModelWhenItWorks() {
        var answer = chat(groq, BIG + "," + SMALL).streamPreferFast(answers(Map.of(
                bigPrompt, Flux.just("fast ", "answer"), smallPrompt, Flux.just("small"), geminiPrompt, Flux.just("slow"))));
        assertThat(answer.collectList().block()).containsExactly("fast ", "answer");
    }

    @Test
    void triesTheNextGroqModelWhenOneIsOutOfQuota() {
        var answer = chat(groq, BIG + ", " + SMALL).streamPreferFast(answers(Map.of(
                bigPrompt, Flux.error(new IllegalStateException("429 rate limited")),
                smallPrompt, Flux.just("small answer"), geminiPrompt, Flux.just("slow"))));
        assertThat(answer.collectList().block()).containsExactly("small answer");
    }

    @Test
    void handsTheQuestionToGeminiWhenEveryGroqModelFails() {
        var answer = chat(groq, BIG + "," + SMALL).streamPreferFast(answers(Map.of(
                bigPrompt, Flux.error(new IllegalStateException("429 rate limited")),
                smallPrompt, Flux.error(new IllegalStateException("503 unavailable")),
                geminiPrompt, Flux.just("gemini answer"))));
        assertThat(answer.collectList().block()).containsExactly("gemini answer");
    }

    @Test
    void neverMixesTwoAnswersWhenGroqFailsHalfway() {
        var halfAnswer = Flux.just("You spent").concatWith(Flux.error(new IllegalStateException("connection reset")));
        List<String> received = new ArrayList<>();
        var answer = chat(groq, BIG + "," + SMALL).streamPreferFast(answers(Map.of(
                        bigPrompt, halfAnswer, smallPrompt, Flux.just("small"), geminiPrompt, Flux.just("gemini"))))
                .doOnNext(received::add);
        assertThatThrownBy(answer::blockLast).hasMessageContaining("connection reset");
        assertThat(received).containsExactly("You spent");
    }

    @Test
    void usesGeminiWhenNoGroqKeyIsSet() {
        var answer = chat(null, BIG).streamPreferFast(answers(Map.of(geminiPrompt, Flux.just("gemini answer"))));
        assertThat(answer.collectList().block()).containsExactly("gemini answer");
    }

    @SuppressWarnings("unchecked")
    private ResilientChat chat(ChatClient fast, String models) {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fast);
        return new ResilientChat(gemini, provider, models, "");
    }

    /** Answers differently depending on which model the request was sent to. */
    private static Function<ChatClient.ChatClientRequestSpec, Flux<String>> answers(
            Map<ChatClient.ChatClientRequestSpec, Flux<String>> byPrompt) {
        return prompt -> {
            assertThat(byPrompt).containsKey(prompt);
            return byPrompt.get(prompt);
        };
    }

    /** Also checks every Groq request turns reasoning off, or follow-up calls fail with a 400. */
    private static boolean isModel(Object builder, String model) {
        if (!(builder instanceof OpenAiChatOptions.Builder b)) {
            return false;
        }
        OpenAiChatOptions options = b.build();
        return model.equals(options.getModel())
                && Boolean.FALSE.equals(options.getExtraBody().get("include_reasoning"));
    }
}
