create table ontology_change (
    id              text primary key,
    project_id      text not null,
    user_id         text,
    username        text,
    changed_at      timestamptz,
    change_type     text,
    change_category text,
    entity_iri      text,
    entity_label    text,
    old_value       text,
    new_value       text,
    axiom_before    text,
    axiom_after     text,
    description     text,
    comment         text,
    metadata        jsonb,
    reverted        boolean not null default false,
    reverted_by     text,
    reverted_at     timestamptz,
    session_id      text,
    ip_address      text
);

create index ontology_change_project_time on ontology_change (project_id, changed_at desc, id desc);
create index ontology_change_user_time on ontology_change (user_id, changed_at desc);
create index ontology_change_project_entity on ontology_change (project_id, entity_iri, changed_at desc);
create index ontology_change_session on ontology_change (session_id, changed_at desc);
create index ontology_change_project_type on ontology_change (project_id, change_type, changed_at desc);
create index ontology_change_project_category on ontology_change (project_id, change_category, changed_at desc);
create index ontology_change_project_reverted on ontology_change (project_id, changed_at desc) where reverted = true;
