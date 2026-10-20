package com.spendwitz.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.spendwitz.ai.GroqChat;
import com.spendwitz.ai.ResilientChat;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.ObservationRegistry;

/** Sends a real request through the Groq client to a fake server and checks what actually goes over the wire. */
class GroqRequestTest {

    private static final String STREAM = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startFakeGroq() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = STREAM.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void groqRequestsAreSentLikeMediZons() {
        String models = "openai/gpt-oss-120b,openai/gpt-oss-20b";
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        ChatClient groq = new AiConfig().fastChatClient("test-key", baseUrl, models, mock(ToolCallingManager.class),
                new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
        var groqChat = new GroqChat(
                new StaticListableBeanFactory(Map.of("fastChatClient", groq)).getBeanProvider(ChatClient.class),
                models, Clock.systemUTC());
        var resilient = new ResilientChat(mock(ChatClient.class), groqChat, "");

        var answer = resilient.streamPreferFast(prompt -> prompt.user("How much did I spend?").stream().content());

        assertThat(answer.collectList().block()).containsExactly("Hi");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"model\":\"openai/gpt-oss-120b\"");
            assertThat(body).contains("\"include_reasoning\":false");
            assertThat(body).contains("\"reasoning_effort\":\"low\"");
            assertThat(body).contains("\"max_completion_tokens\":1024");
            assertThat(body).doesNotContain("reasoning_content");
        });
    }
}
