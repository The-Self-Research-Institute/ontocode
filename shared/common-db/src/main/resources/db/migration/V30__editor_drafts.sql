create table draft_change (
    id             text primary key,
    project_id     text not null,
    user_id        text,
    username       text,
    operation_type text,
    operation_data jsonb,
    changed_at     timestamptz,
    session_id     text,
    applied        boolean not null default false
);

create index draft_change_project_time on draft_change (project_id, changed_at);
create index draft_change_project_user on draft_change (project_id, user_id);
create index draft_change_project_session on draft_change (project_id, user_id, session_id);
create index draft_change_pending_time on draft_change (changed_at) where applied = false;

create table draft_change_parked (
    id             text primary key,
    project_id     text not null,
    user_id        text,
    username       text,
    operation_type text,
    operation_data jsonb,
    changed_at     timestamptz,
    session_id     text,
    applied        boolean not null default false
);

create index draft_change_parked_session on draft_change_parked (project_id, user_id, session_id);

create table draft_session (
    id                      text primary key,
    project_id              text not null,
    user_id                 text not null,
    baseline_main_revision  bigint not null default 0,
    baseline_main_triple_count bigint not null default 0,
    baseline_at             timestamptz,
    baseline_snapshot_path  text,
    copy_status             text,
    prefixes                jsonb
);

create unique index draft_session_project_user on draft_session (project_id, user_id);
create index draft_session_user_prefix on draft_session (user_id text_pattern_ops);

create table draft_pull_request (
    id                        text primary key,
    project_id                text not null,
    author_id                 text,
    author_username           text,
    title                     text,
    description               text,
    status                    text not null default 'OPEN',
    change_count              integer not null default 0,
    submitted_change_ids      text[],
    snapshot_key              text,
    published_change_set_ids  text[],
    reverted_at               timestamptz,
    reverted_by               text,
    revert_reason             text,
    restored_at               timestamptz,
    revert_started_at         timestamptz,
    created_at                timestamptz,
    reviewed_at               timestamptz,
    reviewer_id               text,
    review_note               text
);

create index draft_pull_request_project_status on draft_pull_request (project_id, status, created_at desc);
create index draft_pull_request_project_author on draft_pull_request (project_id, author_id);

create table draft_pr_change (
    id               text primary key,
    seq              bigint generated always as identity,
    pr_id            text not null,
    project_id       text,
    snapshot_key     text,
    change_id        text,
    change_user_id   text,
    change_username  text,
    change_type      text,
    change_data      jsonb,
    change_at        timestamptz,
    change_session_id text,
    change_applied   boolean not null default false,
    has_change       boolean not null default true
);

create index draft_pr_change_pr on draft_pr_change (pr_id, snapshot_key, seq);
create index draft_pr_change_project on draft_pr_change (project_id);
