create table history_change (
    id                  text primary key,
    project_id          text not null,
    edit_id             text,
    user_id             text,
    username            text,
    changed_at          timestamptz,
    operation_type      text,
    entity_type         text,
    entity_iri          text,
    entity_label        text,
    old_value           text,
    new_value           text,
    annotation_property text,
    description         text,
    status              text not null default 'SAVED',
    approved_by         text,
    approved_at         timestamptz,
    rejected_by         text,
    rejected_at         timestamptz,
    comments            jsonb,
    has_conflict        boolean not null default false,
    conflict_resolution text,
    resolved_by         text,
    resolved_at         timestamptz,
    metadata            jsonb,
    sub_changes         jsonb,
    draft               boolean not null default false,
    draft_discarded     boolean not null default false,
    draft_session_id    text,
    published_pr_id     text,
    reverted            boolean not null default false,
    reverted_audit_id   text,
    change_set_id       text,
    exact_deleted       text,
    exact_inserted      text,
    covered_by          text,
    exact_covers        integer not null default 0,
    document_store      text,
    undo_blocked_reason text,
    operation_group_id  text,
    document_id         text,
    document_before     text,
    document_after      text,
    source              text,
    ai                  jsonb,
    synced_at           timestamptz,
    format              text not null default 'json'
);

create index history_change_project_draft_user_time on history_change (project_id, draft, user_id, changed_at desc);
create index history_change_project_entity_time on history_change (project_id, entity_iri, changed_at desc);
create index history_change_project_change_set on history_change (project_id, change_set_id);
create index history_change_project_draft_time on history_change (project_id, draft, changed_at desc);
create index history_change_project_time on history_change (project_id, changed_at desc, id desc);
create index history_change_project_published_pr on history_change (project_id, published_pr_id) where published_pr_id is not null;
create index history_change_project_edit on history_change (project_id, edit_id);
create index history_change_edit on history_change (edit_id);
create index history_change_operation_group on history_change (operation_group_id) where operation_group_id is not null;
create index history_change_import on history_change (project_id, (metadata ->> 'importId')) where metadata ? 'importId';
create index history_change_reverts on history_change (project_id, (metadata ->> 'revertsChangeSetId')) where source = 'ROLLBACK';
create index history_change_username on history_change (project_id, username) where username is not null;
