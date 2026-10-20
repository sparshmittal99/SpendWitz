package com.spendwitz.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.spendwitz.IntegrationTest;

class CachingEmbeddingModelTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    /** Records which texts reached the "API" and returns a predictable vector per text. */
    static class CountingModel implements EmbeddingModel {

        final List<String> calls = new ArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.addAll(request.getInstructions());
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(new float[] {request.getInstructions().get(i).length(), 1f, 2f}, i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }
    }

    @Test
    void onlyNewTextsReachTheApi() {
        var api = new CountingModel();
        var model = new CachingEmbeddingModel(api, jdbc, "test-" + UUID.randomUUID());

        List<float[]> first = model.embed(List.of("Chai", "Uber", "Chai"));
        List<float[]> second = model.embed(List.of("Uber", "Sabzi", "Chai"));

        assertThat(api.calls).containsExactly("Chai", "Uber", "Sabzi");
        assertThat(first.get(0)).containsExactly(4f, 1f, 2f);
        assertThat(first.get(2)).containsExactly(first.get(0));
        assertThat(second.get(1)).containsExactly(5f, 1f, 2f);
    }

    @Test
    void theCacheSurvivesANewInstance() {
        String modelKey = "test-" + UUID.randomUUID();
        new CachingEmbeddingModel(new CountingModel(), jdbc, modelKey).embed("Auto to metro");

        var api = new CountingModel();
        float[] again = new CachingEmbeddingModel(api, jdbc, modelKey).embed("Auto to metro");

        assertThat(api.calls).isEmpty();
        assertThat(again).containsExactly(13f, 1f, 2f);
    }
}
