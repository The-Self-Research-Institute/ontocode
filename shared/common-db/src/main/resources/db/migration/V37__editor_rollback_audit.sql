create table rollback_audit (
    id                       text primary key,
    project_id               text not null,
    history_change_id        text,
    sub_change_id            text,
    entity_iri               text,
    entity_label             text,
    predicate                text,
    action                   text,
    reverted_by_user_id      text,
    reverted_by_username     text,
    reverted_at              timestamptz,
    cascaded                 boolean not null default false,
    cascaded_sub_change_ids  text[],
    change_set_id            text,
    direction                text not null default 'UNDO',
    change_ids               text[],
    skipped_count            integer not null default 0,
    draft_records            jsonb,
    format                   text not null default 'json'
);

create index rollback_audit_project on rollback_audit (project_id);
create index rollback_audit_history_change on rollback_audit (history_change_id, sub_change_id);
create index rollback_audit_change_set on rollback_audit (change_set_id, direction, reverted_at desc);
create index rollback_audit_project_user on rollback_audit (project_id, direction, reverted_by_user_id, reverted_at desc);
