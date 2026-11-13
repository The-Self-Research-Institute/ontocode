create table reasoning_job (
    job_id             text        primary key,
    job_type           text,
    project_id         text,
    status             text        not null,
    queued_at          timestamptz,
    started_at         timestamptz,
    completed_at       timestamptz,
    updated_at         timestamptz not null,
    execution_time_ms  bigint      not null default 0,
    error              text,
    result_json        text,
    result_too_large   boolean     not null default false
);

create index reasoning_job_status on reasoning_job (status);
create index reasoning_job_updated on reasoning_job (updated_at);

create table reasoning_snapshot (
    id                text        primary key,
    project_id        text        not null,
    revision          bigint      not null,
    triple_count      bigint      not null,
    reasoner          text,
    rules_fingerprint text,
    result_json       text,
    created_at        timestamptz,
    updated_at        timestamptz not null
);

create index reasoning_snapshot_project_revision on reasoning_snapshot (project_id, revision);
create index reasoning_snapshot_updated on reasoning_snapshot (updated_at);
