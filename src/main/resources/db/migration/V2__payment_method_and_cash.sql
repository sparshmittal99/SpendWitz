-- How an expense was paid. Existing rows predate the field; UPI is the most common way people pay in India.
alter table expense add column payment_method varchar(20) not null default 'UPI';
create index idx_expense_user_payment on expense (user_id, payment_method, expense_date desc);

-- Cash coming into the wallet (ATM withdrawals, cash received), so "cash in hand" can be tracked
create table cash_topup (
    id         bigserial primary key,
    user_id    bigint         not null references app_user (id) on delete cascade,
    amount     numeric(12, 2) not null check (amount > 0),
    entry_date date           not null,
    note       varchar(200),
    created_at timestamptz    not null default now()
);
create index idx_cash_topup_user_date on cash_topup (user_id, entry_date desc);
