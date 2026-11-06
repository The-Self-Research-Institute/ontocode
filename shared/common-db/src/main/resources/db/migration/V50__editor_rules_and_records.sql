create table datatype_definition (
    id              text primary key,
    project_id      text not null,
    datatype_iri    text,
    expression      text,
    definition_type text,
    created_at      timestamptz,
    updated_at      timestamptz,
    draft           boolean not null default false,
    owner_user_id   text,
    replaces_id     text,
    deleted         boolean not null default false
);

create index datatype_definition_project_iri on datatype_definition (project_id, datatype_iri, created_at desc);
create index datatype_definition_project_owner on datatype_definition (project_id, owner_user_id) where draft;

create table fuzzy_rule (
    id             text primary key,
    project_id     text not null,
    name           text,
    rule_condition text,
    rule_action    text,
    enabled        boolean not null default true,
    created_at     timestamptz,
    updated_at     timestamptz
);

create index fuzzy_rule_project on fuzzy_rule (project_id);

create table sparql_query (
    id         text primary key,
    project_id text not null,
    name       text,
    query_text text,
    created_at timestamptz,
    updated_at timestamptz
);

create index sparql_query_project on sparql_query (project_id);

create table issue_report (
    id                   text primary key,
    title                text,
    description          text,
    steps_to_reproduce   text,
    user_email           text,
    reporter_username    text,
    reporter_email       text,
    project_id           text,
    project_name         text,
    ontology_file_path   text,
    system_info          jsonb,
    attachment_file_names text[],
    error_logs           text,
    issue_type           text,
    priority             text,
    created_at           timestamptz,
    issue_key            text,
    issue_url            text,
    status               text,
    failure_reason       text
);

create index issue_report_user_email on issue_report (user_email, created_at desc);
create index issue_report_reporter_email on issue_report (reporter_email);
create index issue_report_project on issue_report (project_id, created_at desc);
create index issue_report_status on issue_report (status, created_at desc);
create index issue_report_created on issue_report (created_at);

create table spreadsheet_import_record (
    id                    text primary key,
    project_id            text not null,
    user_id               text,
    username              text,
    file_name             text,
    draft                 boolean not null default false,
    draft_session_id      text,
    created_at            timestamptz,
    axiom_count           integer not null default 0,
    already_present_count integer not null default 0,
    added_triples         text[],
    rolled_back           boolean not null default false,
    rolled_back_at        timestamptz,
    rolled_back_by        text
);

create index spreadsheet_import_record_project on spreadsheet_import_record (project_id);
create index spreadsheet_import_record_draft on spreadsheet_import_record (project_id, user_id) where draft;

create table spreadsheet_rule_set (
    id                 text primary key,
    project_id         text not null,
    name               text not null,
    description        text,
    rules              jsonb not null default '[]'::jsonb,
    created_by         text,
    created_by_user_id text,
    updated_by         text,
    created_at         timestamptz,
    updated_at         timestamptz
);

create unique index spreadsheet_rule_set_project_name on spreadsheet_rule_set (project_id, name);
create index spreadsheet_rule_set_project_updated on spreadsheet_rule_set (project_id, updated_at desc);
