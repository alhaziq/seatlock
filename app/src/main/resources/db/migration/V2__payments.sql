alter table events add column price_cents bigint not null default 1 check (price_cents > 0);
alter table events add column currency text not null default 'MYR' check (currency ~ '^[A-Z]{3}$');
alter table events alter column price_cents drop default;
alter table events alter column currency drop default;

alter table holds drop constraint holds_status_check;
alter table holds add constraint holds_status_check
    check (status in ('ACTIVE', 'EXPIRED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED'));

create table payments (
    id              uuid primary key,
    hold_id         uuid not null unique references holds(id),
    user_id         uuid not null,
    idempotency_key text not null,
    amount_cents    bigint not null check (amount_cents > 0),
    currency        text not null,
    status          text not null check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason  text,
    created_at      timestamptz not null,
    settled_at      timestamptz,
    unique (user_id, idempotency_key),
    check ((status = 'PENDING') = (settled_at is null))
);

create table outbox (
    id           uuid primary key,
    topic        text not null,
    message_key  text not null,
    payload      text not null,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on outbox (created_at) where published_at is null;

create table ledger_entries (
    id           uuid primary key,
    payment_id   uuid not null references payments(id),
    event_id     uuid not null references events(id),
    account      text not null check (account in ('PROVIDER_CLEARING', 'EVENT_PAYABLE')),
    direction    text not null check (direction in ('DEBIT', 'CREDIT')),
    amount_cents bigint not null check (amount_cents > 0),
    currency     text not null,
    created_at   timestamptz not null,
    unique (payment_id, account, direction)
);
