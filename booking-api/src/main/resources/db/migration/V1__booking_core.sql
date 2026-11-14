create table events (
    id         uuid primary key,
    name       text not null,
    starts_at  timestamptz not null,
    created_at timestamptz not null
);

create table holds (
    id         uuid primary key,
    event_id   uuid not null references events(id),
    user_id    uuid not null,
    status     text not null check (status in ('ACTIVE', 'EXPIRED')),
    expires_at timestamptz not null,
    created_at timestamptz not null
);
create index holds_active_expiry on holds (expires_at) where status = 'ACTIVE';

create table seats (
    id       uuid primary key,
    event_id uuid not null references events(id),
    label    text not null,
    status   text not null check (status in ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id  uuid references holds(id),
    unique (event_id, label),
    check ((status = 'AVAILABLE') = (hold_id is null))
);
create index seats_hold on seats (hold_id);
