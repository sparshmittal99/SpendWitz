-- Embeddings keyed by a hash of (model, text). Most demo expenses repeat the same text ("Chai", "Uber"...), so the
-- nightly demo reset and restarts on a sleeping free-tier host reuse these instead of calling the embedding API.
create table embedding_cache (
    key        char(64)    primary key,
    embedding  real[]      not null,
    created_at timestamptz not null default now()
);
