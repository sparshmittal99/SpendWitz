package com.spendwitz.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import com.spendwitz.IntegrationTest;

/** With a Groq key the app starts with a second, fast chat client, and Gemini stays the default one. */
@TestPropertySource(properties = "spendwitz.ai.groq.api-key=test-key")
class GroqConfigTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void createsTheFastChatClientOnlyWhenAKeyIsSet() {
        assertThat(context.containsBean("fastChatClient")).isTrue();
        assertThat(context.containsBean("chatClient")).isTrue();
        assertThat(context.getBean(ResilientChat.class)).isNotNull();
    }
}
