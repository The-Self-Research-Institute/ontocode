create table undo_intent (
    id             text primary key,
    project_id     text not null,
    op_id          text,
    direction      text,
    actor          text,
    draft          boolean not null default false,
    change_ids     text[],
    audit_id       text,
    undo_audit_id  text,
    scope_key      text,
    stage          text,
    applied_count  integer not null default 0,
    status         text,
    failure_reason text,
    created_at     timestamptz,
    updated_at     timestamptz
);

create unique index undo_intent_project_op on undo_intent (project_id, op_id) where op_id is not null;
create index undo_intent_project_status on undo_intent (project_id, status);
create index undo_intent_status_created on undo_intent (status, created_at);
