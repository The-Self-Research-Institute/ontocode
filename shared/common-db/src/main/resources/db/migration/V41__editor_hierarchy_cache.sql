create table hierarchy_snapshot (
    project_id             text primary key,
    status                 text not null,
    algorithm_version      text,
    revision               text,
    built_at               bigint not null default 0,
    error_message          text,
    top_level_total        integer not null default 0,
    top_level_computed_limit integer not null default 0,
    meta                   jsonb,
    payload                text,
    expires_at             timestamptz
);

create index hierarchy_snapshot_expires on hierarchy_snapshot (expires_at);

create table top_level_class_cache (
    project_id         text primary key,
    nodes              jsonb,
    computed_with_limit integer not null default 0,
    computed_at        bigint not null default 0,
    expires_at         timestamptz
);

create index top_level_class_cache_expires on top_level_class_cache (expires_at);
