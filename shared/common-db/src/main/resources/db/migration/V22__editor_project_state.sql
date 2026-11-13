create table project_public_graph_version (
    project_id text primary key,
    version    bigint not null default 0,
    updated_at timestamptz
);

create table project_write_lease (
    project_id  text primary key,
    owner_token text not null,
    holder      text,
    acquired_at timestamptz not null,
    expires_at  timestamptz not null
);

create index project_write_lease_expires on project_write_lease (expires_at);

create table project_recovery_lock (
    project_id     text primary key,
    locked         boolean not null default false,
    reason         text,
    locked_at      timestamptz,
    operation_id   text,
    can_restore    boolean not null default false,
    released_by    text,
    release_action text,
    released_at    timestamptz,
    updated_at     timestamptz
);

create table app_migration (
    id           text primary key,
    completed_at timestamptz not null default now(),
    details      jsonb
);
