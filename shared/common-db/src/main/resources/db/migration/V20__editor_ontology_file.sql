create table ontology_file (
    id                  text primary key,
    name                text,
    file_name           text,
    owner_email         text,
    owner_id            text,
    project_id          text,
    workspace_id        text,
    blob_id             text,
    status              text,
    status_message      text,
    created_at          timestamptz,
    updated_at          timestamptz,
    metadata            jsonb,
    mutation_version    bigint,
    main_graph_revision bigint
);

create index ontology_file_project on ontology_file (project_id);
create index ontology_file_workspace on ontology_file (workspace_id);
create index ontology_file_name_owner on ontology_file (file_name, owner_email);
create index ontology_file_status on ontology_file (status);
create index ontology_file_updated on ontology_file (updated_at desc);
create index ontology_file_owner_id on ontology_file (owner_id);
create index ontology_file_owner_email on ontology_file (lower(owner_email));
create index ontology_file_imports on ontology_file using gin ((metadata -> 'imports'));
