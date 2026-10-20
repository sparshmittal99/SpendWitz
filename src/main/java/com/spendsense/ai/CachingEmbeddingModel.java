package com.spendsense.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wraps the real embedding model with a Postgres-backed cache keyed by a hash of the text. Only texts that were
 * never embedded before reach the API, which keeps the free-tier embedding quota safe.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final JdbcTemplate jdbc;
    private final String modelKey;

    public CachingEmbeddingModel(EmbeddingModel delegate, JdbcTemplate jdbc, String modelKey) {
        this.delegate = delegate;
        this.jdbc = jdbc;
        this.modelKey = modelKey;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        Map<String, String> keyByText = new LinkedHashMap<>();
        texts.forEach(t -> keyByText.put(t, key(t)));
        Map<String, float[]> cached = load(new ArrayList<>(keyByText.values()));

        List<String> missing = texts.stream().filter(t -> !cached.containsKey(keyByText.get(t))).distinct().toList();
        if (!missing.isEmpty()) {
            List<Embedding> fresh = delegate.call(new EmbeddingRequest(missing, request.getOptions())).getResults();
            for (int i = 0; i < missing.size(); i++) {
                float[] vector = fresh.get(i).getOutput();
                String key = keyByText.get(missing.get(i));
                cached.put(key, vector);
                store(key, vector);
            }
        }
        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            results.add(new Embedding(cached.get(keyByText.get(texts.get(i))), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private Map<String, float[]> load(List<String> keys) {
        Map<String, float[]> found = new HashMap<>();
        jdbc.query(connection -> {
            var ps = connection.prepareStatement("select key, embedding from embedding_cache where key = any(?)");
            ps.setArray(1, connection.createArrayOf("bpchar", keys.toArray()));
            return ps;
        }, rs -> {
            Array array = rs.getArray(2);
            Float[] values = (Float[]) array.getArray();
            float[] vector = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                vector[i] = values[i];
            }
            found.put(rs.getString(1), vector);
        });
        return found;
    }

    private void store(String key, float[] vector) {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "insert into embedding_cache (key, embedding) values (?, ?) on conflict (key) do nothing");
            ps.setString(1, key);
            ps.setArray(2, connection.createArrayOf("float4", boxed));
            return ps;
        });
    }

    private String key(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((modelKey + "\n" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
