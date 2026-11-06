create table assistant_session (
    id                           text primary key,
    project_id                   text,
    user_email                   text,
    document_path                text,
    action_type                  text,
    action_context               text,
    provider                     text,
    model                        text,
    draft                        boolean not null default false,
    draft_user_id                text,
    pinned_revision              bigint,
    status                       text,
    retrieval_attempts_remaining integer,
    token_budget_remaining       integer,
    deadline_at                  timestamptz,
    created_at                   timestamptz,
    updated_at                   timestamptz,
    expires_at                   timestamptz
);

create index assistant_session_user_status on assistant_session (user_email, status, deadline_at);
create index assistant_session_project on assistant_session (project_id);
create index assistant_session_expires on assistant_session (expires_at);

create table assistant_edit_group (
    id                              text primary key,
    session_id                      text,
    project_id                      text,
    user_email                      text,
    client_group_id                 text,
    target_path                     text,
    draft                           boolean not null default false,
    draft_user_id                   text,
    edits                           jsonb,
    status                          text,
    public_graph_version_at_propose bigint,
    positions_verified_at_version   bigint,
    applied_revision                bigint,
    stale_reason                    text,
    created_at                      timestamptz,
    updated_at                      timestamptz,
    expires_at                      timestamptz,
    applied_at                      timestamptz,
    check_job_id                    text,
    check_state                     text,
    check_detail                    text,
    check_resolved_at               timestamptz
);

create index assistant_edit_group_target on assistant_edit_group (project_id, target_path, status);
create index assistant_edit_group_expires on assistant_edit_group (expires_at);

create table assistant_audit (
    id              text primary key,
    actor           text,
    project_id      text,
    session_id      text,
    group_id        text,
    operation       text,
    source_revision bigint,
    provider        text,
    model           text,
    outcome         text,
    error_code      text,
    detail          text,
    created_at      timestamptz,
    expires_at      timestamptz
);

create index assistant_audit_project_created on assistant_audit (project_id, created_at);
create index assistant_audit_expires on assistant_audit (expires_at);

create table assistant_idempotency (
    id                    text primary key,
    actor                 text,
    method                text,
    path                  text,
    body_hash             text,
    state                 text,
    owner_token           text,
    response_status       integer,
    response_content_type text,
    response_body         text,
    created_at            timestamptz,
    updated_at            timestamptz,
    expires_at            timestamptz
);

create index assistant_idempotency_expires on assistant_idempotency (expires_at);

create table assistant_apply_operation (
    id             text primary key,
    project_id     text,
    group_id       text,
    session_id     text,
    actor          text,
    target_path    text,
    status         text,
    snapshot_path  text,
    failure_reason text,
    resolution     text,
    created_at     timestamptz,
    updated_at     timestamptz,
    resolved_at    timestamptz
);

create index assistant_apply_operation_group on assistant_apply_operation (group_id, created_at);
create index assistant_apply_operation_project_status on assistant_apply_operation (project_id, status);
create index assistant_apply_operation_status_updated on assistant_apply_operation (status, updated_at);
