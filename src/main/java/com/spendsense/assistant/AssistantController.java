package com.spendsense.assistant;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.spendsense.common.QuotaExceededException;
import com.spendsense.user.AuthUser;

import reactor.core.publisher.Flux;

@Controller
@RequestMapping("/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {
    }

    /** One line of the NDJSON stream: either a piece of text or an error message. */
    public record ChatChunk(String text, String error) {

        static ChatChunk text(String text) {
            return new ChatChunk(text, null);
        }

        static ChatChunk error(String error) {
            return new ChatChunk(null, error);
        }
    }

    @GetMapping
    public String page() {
        return "assistant/index";
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_NDJSON_VALUE)
    @ResponseBody
    public Flux<ChatChunk> chat(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChatRequest request,
            HttpSession session) {
        String conversationId = conversationId(user, session);
        return Flux.defer(() -> assistant.chat(user, conversationId, request.message().trim()))
                .map(ChatChunk::text)
                .onErrorResume(e -> {
                    if (e instanceof QuotaExceededException) {
                        return Flux.just(ChatChunk.error(e.getMessage()));
                    }
                    log.warn("Assistant request failed", e);
                    return Flux.just(ChatChunk.error(
                            "The AI service is busy or unavailable right now. Please try again in a minute."));
                });
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@AuthenticationPrincipal AuthUser user, HttpSession session) {
        assistant.reset(conversationId(user, session));
        return ResponseEntity.noContent().build();
    }

    /** Per browser session, so visitors sharing the demo account don't see each other's chats. */
    private static String conversationId(AuthUser user, HttpSession session) {
        return "user-" + user.id() + "-" + session.getId();
    }
}
