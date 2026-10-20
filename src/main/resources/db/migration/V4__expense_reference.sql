-- The bill / invoice / order number or UPI transaction id read from a scanned bill. Never shown to the user;
-- used to spot a bill that's uploaded again.
alter table expense add column reference varchar(100);
create index idx_expense_user_reference on expense (user_id, reference) where reference is not null;
