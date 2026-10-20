create extension if not exists vector;

create table app_user (
    id            bigserial primary key,
    email         varchar(255) not null unique,
    password_hash varchar(100) not null,
    display_name  varchar(100) not null,
    currency      varchar(3)   not null default 'INR',
    demo          boolean      not null default false,
    created_at    timestamptz  not null default now()
);

create table expense (
    id           bigserial primary key,
    user_id      bigint        not null references app_user (id) on delete cascade,
    merchant     varchar(150)  not null,
    amount       numeric(12, 2) not null check (amount > 0),
    category     varchar(30)   not null,
    expense_date date          not null,
    note         varchar(500),
    source       varchar(20)   not null,
    created_at   timestamptz   not null default now()
);
create index idx_expense_user_date on expense (user_id, expense_date desc);

create table expense_item (
    id         bigserial primary key,
    expense_id bigint        not null references expense (id) on delete cascade,
    position   int           not null,
    name       varchar(200)  not null,
    quantity   numeric(10, 2),
    price      numeric(12, 2)
);
create index idx_expense_item_expense on expense_item (expense_id);

create table budget (
    id            bigserial primary key,
    user_id       bigint         not null references app_user (id) on delete cascade,
    category      varchar(30)    not null,
    monthly_limit numeric(12, 2) not null check (monthly_limit > 0),
    unique (user_id, category)
);

create table monthly_insight (
    id           bigserial primary key,
    user_id      bigint      not null references app_user (id) on delete cascade,
    month        varchar(7)  not null,
    content      text        not null,
    generated_at timestamptz not null default now(),
    unique (user_id, month)
);

-- Daily AI call counters. user_id 0 is the app-wide counter, so there is no foreign key.
create table ai_usage (
    user_id       bigint not null,
    usage_date    date   not null,
    request_count int    not null,
    primary key (user_id, usage_date)
);

-- Spring AI PgVectorStore table (768-dim Gemini embeddings, cosine distance)
create table vector_store (
    id        uuid primary key,
    content   text,
    metadata  json,
    embedding vector(768)
);
create index idx_vector_store_embedding on vector_store using hnsw (embedding vector_cosine_ops);
