package com.spendwitz.push;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

/**
 * Sends phone notifications through Firebase Cloud Messaging (HTTP v1 API). Android shows them even when the app is
 * closed. Off unless FIREBASE_CREDENTIALS holds the Firebase service account key (the JSON, on one line).
 */
@Component
public class FcmSender {

    public enum Result { SENT, INVALID_TOKEN, FAILED }

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final GoogleCredentials credentials;
    private final String projectId;
    private final RestClient http = RestClient.create("https://fcm.googleapis.com/v1/");

    public FcmSender(@Value("${spendwitz.push.firebase-credentials:}") String serviceAccountJson) {
        GoogleCredentials creds = null;
        String project = null;
        if (serviceAccountJson.isBlank()) {
            log.info("Phone notifications are off: FIREBASE_CREDENTIALS is not set");
        } else {
            try {
                var account = ServiceAccountCredentials.fromStream(
                        new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
                creds = account.createScoped(SCOPE);
                project = account.getProjectId();
            } catch (IOException | RuntimeException e) {
                log.error("Phone notifications are off: FIREBASE_CREDENTIALS is not a valid service account key");
            }
        }
        this.credentials = creds;
        this.projectId = project;
    }

    public boolean enabled() {
        return credentials != null && projectId != null;
    }

    /** One notification to one phone. {@code tag} replaces an earlier notification with the same tag. */
    public Result send(String token, String title, String body, String tag, Map<String, String> data) {
        if (!enabled()) {
            return Result.FAILED;
        }
        String message = """
                {"message":{"token":%s,"notification":{"title":%s,"body":%s},\
                "android":{"priority":"high","notification":{"channel_id":"budget_alerts","icon":"ic_stat_wallet",\
                "color":"#4F46E5","tag":%s}},"data":%s}}"""
                .formatted(json(token), json(title), json(body), json(tag), json(data));
        try {
            credentials.refreshIfExpired();
            http.post()
                    .uri("projects/{project}/messages:send", projectId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.getAccessToken().getTokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
            return Result.SENT;
        } catch (HttpClientErrorException e) {
            // The app was uninstalled, its data cleared, or alerts turned off: this phone's token is gone for good
            if (e.getStatusCode().value() == 404 || e.getResponseBodyAsString().contains("UNREGISTERED")) {
                return Result.INVALID_TOKEN;
            }
            log.warn("Firebase rejected a notification: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Result.FAILED;
        } catch (IOException | RuntimeException e) {
            log.warn("Couldn't send a notification through Firebase: {}", e.toString());
            return Result.FAILED;
        }
    }

    private static String json(Map<String, String> map) {
        return map.entrySet().stream()
                .map(e -> json(e.getKey()) + ":" + json(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
