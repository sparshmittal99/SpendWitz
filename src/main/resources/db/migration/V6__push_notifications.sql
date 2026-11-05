-- Phones signed in to the Android app, by their Firebase Cloud Messaging token. A token belongs to one user at a time:
-- signing in as someone else on the same phone moves it.
create table push_token (
    token      varchar(4096) primary key,
    user_id    bigint       not null references app_user (id) on delete cascade,
    updated_at timestamptz  not null default now()
);

create index push_token_user_idx on push_token (user_id);

-- Over-budget notifications already sent, so each category alerts once a month. Cleared when the category is back
-- under its limit, so going over again alerts again.
create table budget_alert_sent (
    user_id  bigint      not null references app_user (id) on delete cascade,
    category varchar(40) not null,
    month    varchar(7)  not null,
    primary key (user_id, category, month)
);
