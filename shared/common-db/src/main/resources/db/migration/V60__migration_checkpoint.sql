create table migration_checkpoint (
    collection   text primary key,
    last_id      text,
    rows_read    bigint      not null default 0,
    rows_written bigint      not null default 0,
    rows_skipped bigint      not null default 0,
    status       text        not null default 'running',
    updated_at   timestamptz not null default now()
);
