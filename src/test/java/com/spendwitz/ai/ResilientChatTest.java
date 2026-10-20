package com.spendwitz.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;

class ResilientChatTest {

    private final ChatClient gemini = mock(ChatClient.class);
    private final ChatClient groq = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec geminiPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.ChatClientRequestSpec groqPrompt = mock(ChatClient.ChatClientRequestSpec.class);

    ResilientChatTest() {
        when(gemini.prompt()).thenReturn(geminiPrompt);
        when(groq.prompt()).thenReturn(groqPrompt);
    }

    @Test
    void answersWithGroqWhenItWorks() {
        var answer = chat(groq).streamPreferFast(answer(Flux.just("fast ", "answer"), Flux.just("slow")));
        assertThat(answer.collectList().block()).containsExactly("fast ", "answer");
    }

    @Test
    void handsTheQuestionToGeminiWhenGroqFailsBeforeAnswering() {
        var groqDown = Flux.<String>error(new IllegalStateException("429 rate limited"));
        var answer = chat(groq).streamPreferFast(answer(groqDown, Flux.just("gemini answer")));
        assertThat(answer.collectList().block()).containsExactly("gemini answer");
    }

    @Test
    void neverMixesTwoAnswersWhenGroqFailsHalfway() {
        var halfAnswer = Flux.just("You spent").concatWith(Flux.error(new IllegalStateException("connection reset")));
        List<String> received = new ArrayList<>();
        var answer = chat(groq).streamPreferFast(answer(halfAnswer, Flux.just("gemini answer")))
                .doOnNext(received::add);
        assertThatThrownBy(answer::blockLast).hasMessageContaining("connection reset");
        assertThat(received).containsExactly("You spent");
    }

    @Test
    void usesGeminiWhenNoGroqKeyIsSet() {
        var answer = chat(null).streamPreferFast(answer(Flux.just("fast"), Flux.just("gemini answer")));
        assertThat(answer.collectList().block()).containsExactly("gemini answer");
    }

    @SuppressWarnings("unchecked")
    private ResilientChat chat(ChatClient fast) {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fast);
        return new ResilientChat(gemini, provider, "");
    }

    /** Answers differently depending on which model the request was sent to. */
    private Function<ChatClient.ChatClientRequestSpec, Flux<String>> answer(Flux<String> fromGroq,
            Flux<String> fromGemini) {
        return prompt -> {
            assertThat(prompt).isIn(groqPrompt, geminiPrompt);
            return prompt == groqPrompt ? fromGroq : fromGemini;
        };
    }
}
