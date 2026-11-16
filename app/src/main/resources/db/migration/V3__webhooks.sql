alter table events add column organizer_id uuid;

create table webhook_endpoints (
    id           uuid primary key,
    organizer_id uuid not null unique,
    url          text not null,
    secret       text not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

create table webhook_deliveries (
    id              uuid primary key,
    endpoint_id     uuid not null references webhook_endpoints(id),
    organizer_id    uuid not null,
    type            text not null check (type in ('booking.confirmed', 'booking.expired')),
    hold_id         uuid not null references holds(id),
    payload         text not null,
    status          text not null check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        int not null default 0,
    next_attempt_at timestamptz not null,
    created_at      timestamptz not null,
    delivered_at    timestamptz,
    unique (type, hold_id),
    check ((status = 'DELIVERED') = (delivered_at is not null))
);

create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at) where status = 'PENDING';
create index webhook_deliveries_organizer_idx on webhook_deliveries (organizer_id, created_at desc);

create table webhook_attempts (
    id            uuid primary key,
    delivery_id   uuid not null references webhook_deliveries(id),
    attempt       int not null,
    attempted_at  timestamptz not null,
    status_code   int,
    error         text,
    response_body text,
    duration_ms   bigint not null,
    unique (delivery_id, attempt)
);
